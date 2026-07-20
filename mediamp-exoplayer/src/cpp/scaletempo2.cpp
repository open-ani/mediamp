// This file was ported from mpv (https://github.com/mpv-player/mpv),
// audio/filter/af_scaletempo2_internals.c, which was in turn ported from Chromium
// (https://chromium.googlesource.com/chromium/chromium/+/51ed77e3f37a9a9b80d6d0a8259e84a8ca635259/media/filters/audio_renderer_algorithm.cc)
//
// Copyright 2015 The Chromium Authors. All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are
// met:
//
//    * Redistributions of source code must retain the above copyright
// notice, this list of conditions and the following disclaimer.
//    * Redistributions in binary form must reproduce the above
// copyright notice, this list of conditions and the following disclaimer
// in the documentation and/or other materials provided with the
// distribution.
//    * Neither the name of Google Inc. nor the names of its
// contributors may be used to endorse or promote products derived from
// this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
// OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
// LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
// DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
// THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
//
// Port notes (mediamp):
//  - Faithful C++17 port of the WSOLA core only; no mpv infrastructure.
//  - talloc arrays -> std::vector; MP_TARRAY_GROW -> grow_to_fit; mp_assert -> assert.
//  - The scalar (non-HAVE_VECTOR) multi_channel_dot_product is used; clang
//    auto-vectorizes it well enough for 12 ms windows at mono/stereo.

#include "scaletempo2.h"

#include <cassert>
#include <cfloat>
#include <cmath>
#include <cstring>

namespace wsola {

namespace {

#define MPMAX(a, b) ((a) > (b) ? (a) : (b))
#define MPMIN(a, b) ((a) < (b) ? (a) : (b))

// Algorithm overview (from chromium):
// Waveform Similarity Overlap-and-add (WSOLA).
//
// One WSOLA iteration
//
// 1) Extract |target_block| as input frames at indices
//    [|target_block_index|, |target_block_index| + |ola_window_size|).
//    Note that |target_block| is the "natural" continuation of the output.
//
// 2) Extract |search_block| as input frames at indices
//    [|search_block_index|,
//     |search_block_index| + |num_candidate_blocks| + |ola_window_size|).
//
// 3) Find a block within the |search_block| that is most similar
//    to |target_block|. Let |optimal_index| be the index of such block and
//    write it to |optimal_block|.
//
// 4) Update:
//    |optimal_block| = |transition_window| * |target_block| +
//    (1 - |transition_window|) * |optimal_block|.
//
// 5) Overlap-and-add |optimal_block| to the |wsola_output|.
//
// 6) Update:write

struct interval {
    int lo;
    int hi;
};

bool in_interval(int n, interval q)
{
    return n >= q.lo && n <= q.hi;
}

// MP_TARRAY_GROW replacement: ensure the vector can be indexed up to
// |last_index| (contents preserved, like talloc's GROW).
void grow_to_fit(std::vector<float> &v, int last_index)
{
    if (last_index >= 0 && static_cast<size_t>(last_index) >= v.size()) {
        v.resize(static_cast<size_t>(last_index) + 1);
    }
}

void alloc_sample_buffer(mp_scaletempo2 *p,
                         std::vector<std::vector<float>> *ptr, size_t size)
{
    ptr->assign(static_cast<size_t>(p->channels), std::vector<float>(size, 0.0f));
}

void zero_2d_partial(std::vector<std::vector<float>> &a, int x, int y)
{
    for (int i = 0; i < x; ++i) {
        std::memset(a[i].data(), 0, sizeof(float) * static_cast<size_t>(y));
    }
}

// Energies of sliding windows of channels are interleaved.
// The number windows is |input_frames| - (|frames_per_window| - 1), hence,
// the method assumes |energy| must be, at least, of size
// (|input_frames| - (|frames_per_window| - 1)) * |channels|.
void multi_channel_moving_block_energies(
    std::vector<std::vector<float>> &input, int input_frames, int channels,
    int frames_per_block, float *energy)
{
    int num_blocks = input_frames - (frames_per_block - 1);

    for (int k = 0; k < channels; ++k) {
        const float *input_channel = input[k].data();

        energy[k] = 0;

        // First block of channel |k|.
        for (int m = 0; m < frames_per_block; ++m) {
            energy[k] += input_channel[m] * input_channel[m];
        }

        const float *slide_out = input_channel;
        const float *slide_in = input_channel + frames_per_block;
        for (int n = 1; n < num_blocks; ++n, ++slide_in, ++slide_out) {
            energy[k + n * channels] = energy[k + (n - 1) * channels]
                - *slide_out * *slide_out + *slide_in * *slide_in;
        }
    }
}

float multi_channel_similarity_measure(
    const float *dot_prod,
    const float *energy_target, const float *energy_candidate,
    int channels)
{
    const float epsilon = 1e-12f;
    float similarity_measure = 0.0f;
    for (int n = 0; n < channels; ++n) {
        similarity_measure += dot_prod[n] * energy_target[n]
            / sqrtf(energy_target[n] * energy_candidate[n] + epsilon);
    }
    return similarity_measure;
}

// Dot-product of channels of two AudioBus. For each AudioBus an offset is
// given. |dot_product[k]| is the dot-product of channel |k|. The caller should
// allocate sufficient space for |dot_product|.
void multi_channel_dot_product(
    std::vector<std::vector<float>> &a, int frame_offset_a,
    std::vector<std::vector<float>> &b, int frame_offset_b,
    int channels,
    int num_frames, float *dot_product)
{
    assert(frame_offset_a >= 0);
    assert(frame_offset_b >= 0);

    for (int k = 0; k < channels; ++k) {
        const float *ch_a = a[k].data() + frame_offset_a;
        const float *ch_b = b[k].data() + frame_offset_b;
        float sum = 0.0f;
        for (int n = 0; n < num_frames; n++)
            sum += *ch_a++ * *ch_b++;
        dot_product[k] = sum;
    }
}

// Fit the curve f(x) = a * x^2 + b * x + c such that
//   f(-1) = y[0]
//   f(0) = y[1]
//   f(1) = y[2]
// and return the maximum, assuming that y[0] <= y[1] >= y[2].
void quadratic_interpolation(
    const float *y_values, float *extremum, float *extremum_value)
{
    float a = 0.5f * (y_values[2] + y_values[0]) - y_values[1];
    float b = 0.5f * (y_values[2] - y_values[0]);
    float c = y_values[1];

    if (a == 0.f) {
        // The coordinates are colinear (within floating-point error).
        *extremum = 0;
        *extremum_value = y_values[1];
    } else {
        *extremum = -b / (2.f * a);
        *extremum_value = a * (*extremum) * (*extremum) + b * (*extremum) + c;
    }
}

// Search a subset of all candid blocks. The search is performed every
// |decimation| frames. This reduces complexity by a factor of about
// 1 / |decimation|. A cubic interpolation is used to have a better estimate of
// the best match.
int decimated_search(
    int decimation, interval exclude_interval,
    std::vector<std::vector<float>> &target_block, int target_block_frames,
    std::vector<std::vector<float>> &search_segment, int search_segment_frames,
    int channels,
    const float *energy_target_block, const float *energy_candidate_blocks)
{
    int num_candidate_blocks = search_segment_frames - (target_block_frames - 1);
    float dot_prod[WSOLA_MAX_CHANNELS];
    float similarity[3];  // Three elements for cubic interpolation.

    int n = 0;
    multi_channel_dot_product(
        target_block, 0,
        search_segment, n,
        channels,
        target_block_frames, dot_prod);
    similarity[0] = multi_channel_similarity_measure(
        dot_prod, energy_target_block,
        &energy_candidate_blocks[n * channels], channels);

    // Set the starting point as optimal point.
    float best_similarity = similarity[0];
    int optimal_index = 0;

    n += decimation;
    if (n >= num_candidate_blocks) {
        return 0;
    }

    multi_channel_dot_product(
        target_block, 0,
        search_segment, n,
        channels,
        target_block_frames, dot_prod);
    similarity[1] = multi_channel_similarity_measure(
        dot_prod, energy_target_block,
        &energy_candidate_blocks[n * channels], channels);

    n += decimation;
    if (n >= num_candidate_blocks) {
        // We cannot do any more sampling. Compare these two values and return the
        // optimal index.
        return similarity[1] > similarity[0] ? decimation : 0;
    }

    for (; n < num_candidate_blocks; n += decimation) {
        multi_channel_dot_product(
            target_block, 0,
            search_segment, n,
            channels,
            target_block_frames, dot_prod);

        similarity[2] = multi_channel_similarity_measure(
            dot_prod, energy_target_block,
            &energy_candidate_blocks[n * channels], channels);

        if ((similarity[1] > similarity[0] && similarity[1] >= similarity[2]) ||
            (similarity[1] >= similarity[0] && similarity[1] > similarity[2]))
        {
            // A local maximum is found. Do a cubic interpolation for a better
            // estimate of candidate maximum.
            float normalized_candidate_index;
            float candidate_similarity;
            quadratic_interpolation(similarity, &normalized_candidate_index,
                                    &candidate_similarity);

            int candidate_index = n - decimation
                 + (int)(normalized_candidate_index * decimation + 0.5f);
            if (candidate_similarity > best_similarity
                && !in_interval(candidate_index, exclude_interval)) {
                optimal_index = candidate_index;
                best_similarity = candidate_similarity;
            }
        } else if (n + decimation >= num_candidate_blocks &&
                   similarity[2] > best_similarity &&
                   !in_interval(n, exclude_interval))
        {
            // If this is the end-point and has a better similarity-measure than
            // optimal, then we accept it as optimal point.
            optimal_index = n;
            best_similarity = similarity[2];
        }
        memmove(similarity, &similarity[1], 2 * sizeof(*similarity));
    }
    return optimal_index;
}

// Search [|low_limit|, |high_limit|] of |search_segment| to find a block that
// is most similar to |target_block|. |energy_target_block| is the energy of the
// |target_block|. |energy_candidate_blocks| is the energy of all blocks within
// |search_block|.
int full_search(
    int low_limit, int high_limit,
    interval exclude_interval,
    std::vector<std::vector<float>> &target_block, int target_block_frames,
    std::vector<std::vector<float>> &search_block,
    int channels,
    const float *energy_target_block,
    const float *energy_candidate_blocks)
{
    float dot_prod[WSOLA_MAX_CHANNELS];

    float best_similarity = -FLT_MAX;
    int optimal_index = 0;

    for (int n = low_limit; n <= high_limit; ++n) {
        if (in_interval(n, exclude_interval)) {
            continue;
        }
        multi_channel_dot_product(target_block, 0, search_block, n, channels,
            target_block_frames, dot_prod);

        float similarity = multi_channel_similarity_measure(
            dot_prod, energy_target_block,
            &energy_candidate_blocks[n * channels], channels);

        if (similarity > best_similarity) {
            best_similarity = similarity;
            optimal_index = n;
        }
    }

    return optimal_index;
}

// Find the index of the block, within |search_block|, that is most similar
// to |target_block|. Obviously, the returned index is w.r.t. |search_block|.
// |exclude_interval| is an interval that is excluded from the search.
int compute_optimal_index(
    std::vector<std::vector<float>> &search_block, int search_block_frames,
    std::vector<std::vector<float>> &target_block, int target_block_frames,
    float *energy_candidate_blocks,
    int channels,
    interval exclude_interval)
{
    int num_candidate_blocks = search_block_frames - (target_block_frames - 1);

    // This is a compromise between complexity reduction and search accuracy. I
    // don't have a proof that down sample of order 5 is optimal.
    // One can compute a decimation factor that minimizes complexity given
    // the size of |search_block| and |target_block|. However, my experiments
    // show the rate of missing the optimal index is significant.
    // This value is chosen heuristically based on experiments.
    const int search_decimation = 5;

    float energy_target_block[WSOLA_MAX_CHANNELS];
    // energy_candidate_blocks must have at least size
    // sizeof(float) * channels * num_candidate_blocks

    // Energy of all candid frames.
    multi_channel_moving_block_energies(
        search_block,
        search_block_frames,
        channels,
        target_block_frames,
        energy_candidate_blocks);

    // Energy of target frame.
    multi_channel_dot_product(
        target_block, 0,
        target_block, 0,
        channels,
        target_block_frames, energy_target_block);

    int optimal_index = decimated_search(
        search_decimation, exclude_interval,
        target_block, target_block_frames,
        search_block, search_block_frames,
        channels,
        energy_target_block,
        energy_candidate_blocks);

    int lim_low = MPMAX(0, optimal_index - search_decimation);
    int lim_high = MPMIN(num_candidate_blocks - 1,
                            optimal_index + search_decimation);
    return full_search(
        lim_low, lim_high, exclude_interval,
        target_block, target_block_frames,
        search_block,
        channels,
        energy_target_block, energy_candidate_blocks);
}

void peek_buffer(mp_scaletempo2 *p,
    int frames, int read_offset, int write_offset,
    std::vector<std::vector<float>> &dest)
{
    assert(p->input_buffer_frames >= frames);
    for (int i = 0; i < p->channels; ++i) {
        memcpy(dest[i].data() + write_offset,
            p->input_buffer[i].data() + read_offset,
            static_cast<size_t>(frames) * sizeof(float));
    }
}

void seek_buffer(mp_scaletempo2 *p, int frames)
{
    assert(p->input_buffer_frames >= frames);
    p->input_buffer_frames -= frames;
    if (p->input_buffer_final_frames > 0) {
        p->input_buffer_final_frames = MPMAX(0, p->input_buffer_final_frames - frames);
    }
    for (int i = 0; i < p->channels; ++i) {
        memmove(p->input_buffer[i].data(), p->input_buffer[i].data() + frames,
            static_cast<size_t>(p->input_buffer_frames) * sizeof(float));
    }
}

int write_completed_frames_to(mp_scaletempo2 *p,
    int requested_frames, int dest_offset, float *const *dest)
{
    int rendered_frames = MPMIN(p->num_complete_frames, requested_frames);

    if (rendered_frames == 0)
        return 0;  // There is nothing to read from |wsola_output|, return.

    for (int i = 0; i < p->channels; ++i) {
        memcpy(dest[i] + dest_offset, p->wsola_output[i].data(),
            static_cast<size_t>(rendered_frames) * sizeof(float));
    }

    // Remove the frames which are read.
    int frames_to_move = p->wsola_output_size - rendered_frames;
    for (int k = 0; k < p->channels; ++k) {
        float *ch = p->wsola_output[k].data();
        memmove(ch, &ch[rendered_frames], sizeof(*ch) * static_cast<size_t>(frames_to_move));
    }
    p->num_complete_frames -= rendered_frames;
    return rendered_frames;
}

// next output_time for the given playback_rate
double get_updated_time(mp_scaletempo2 *p, double playback_rate)
{
    return p->output_time + p->ola_hop_size * playback_rate;
}

// search_block_index for the given output_time
int get_search_block_index(mp_scaletempo2 *p, double output_time)
{
    return (int)(output_time - p->search_block_center_offset + 0.5);
}

// number of frames needed until a wsola iteration can be performed
int frames_needed(mp_scaletempo2 *p, double playback_rate)
{
    int search_block_index =
        get_search_block_index(p, get_updated_time(p, playback_rate));
    return MPMAX(0, MPMAX(
        p->target_block_index + p->ola_window_size - p->input_buffer_frames,
        search_block_index + p->search_block_size - p->input_buffer_frames));
}

bool can_perform_wsola(mp_scaletempo2 *p, double playback_rate)
{
    return frames_needed(p, playback_rate) <= 0;
}

// pad end with silence until a wsola iteration can be performed
void add_input_buffer_final_silence(mp_scaletempo2 *p, double playback_rate)
{
    int needed = frames_needed(p, playback_rate);
    if (needed <= 0)
        return; // no silence needed for iteration

    int last_index = needed + p->input_buffer_frames - 1;
    for (int i = 0; i < p->channels; ++i) {
        grow_to_fit(p->input_buffer[i], last_index);
        float *ch_input = p->input_buffer[i].data();
        for (int j = 0; j < needed; ++j) {
            ch_input[p->input_buffer_frames + j] = 0.0f;
        }
    }

    p->input_buffer_added_silence += needed;
    p->input_buffer_frames += needed;
}

bool target_is_within_search_region(mp_scaletempo2 *p)
{
    return p->target_block_index >= p->search_block_index
        && p->target_block_index + p->ola_window_size
            <= p->search_block_index + p->search_block_size;
}

void peek_audio_with_zero_prepend(mp_scaletempo2 *p,
    int read_offset_frames, std::vector<std::vector<float>> &dest, int dest_frames)
{
    assert(read_offset_frames + dest_frames <= p->input_buffer_frames);

    int write_offset = 0;
    int num_frames_to_read = dest_frames;
    if (read_offset_frames < 0) {
        int num_zero_frames_appended = MPMIN(
            -read_offset_frames, num_frames_to_read);
        read_offset_frames = 0;
        num_frames_to_read -= num_zero_frames_appended;
        write_offset = num_zero_frames_appended;
        zero_2d_partial(dest, p->channels, num_zero_frames_appended);
    }
    peek_buffer(p, num_frames_to_read, read_offset_frames, write_offset, dest);
}

void get_optimal_block(mp_scaletempo2 *p)
{
    int optimal_index = 0;

    // An interval around last optimal block which is excluded from the search.
    // This is to reduce the buzzy sound. The number 160 is rather arbitrary and
    // derived heuristically.
    const int exclude_interval_length_frames = 160;
    if (target_is_within_search_region(p)) {
        optimal_index = p->target_block_index;
        peek_audio_with_zero_prepend(p,
            optimal_index, p->optimal_block, p->ola_window_size);
    } else {
        peek_audio_with_zero_prepend(p,
            p->target_block_index, p->target_block, p->ola_window_size);
        peek_audio_with_zero_prepend(p,
            p->search_block_index, p->search_block, p->search_block_size);
        int last_optimal = p->target_block_index
            - p->ola_hop_size - p->search_block_index;
        interval exclude_iterval = {
            .lo = last_optimal - exclude_interval_length_frames / 2,
            .hi = last_optimal + exclude_interval_length_frames / 2
        };

        // |optimal_index| is in frames and it is relative to the beginning of the
        // |search_block|.
        optimal_index = compute_optimal_index(
            p->search_block, p->search_block_size,
            p->target_block, p->ola_window_size,
            p->energy_candidate_blocks.data(),
            p->channels,
            exclude_iterval);

        // Translate |index| w.r.t. the beginning of |audio_buffer| and extract the
        // optimal block.
        optimal_index += p->search_block_index;
        peek_audio_with_zero_prepend(p,
            optimal_index, p->optimal_block, p->ola_window_size);

        // Make a transition from target block to the optimal block if different.
        // Target block has the best continuation to the current output.
        // Optimal block is the most similar block to the target, however, it might
        // introduce some discontinuity when over-lap-added. Therefore, we combine
        // them for a smoother transition. The length of transition window is twice
        // as that of the optimal-block which makes it like a weighting function
        // where target-block has higher weight close to zero (weight of 1 at index
        // 0) and lower weight close the end.
        for (int k = 0; k < p->channels; ++k) {
            float *ch_opt = p->optimal_block[k].data();
            float *ch_target = p->target_block[k].data();
            for (int n = 0; n < p->ola_window_size; ++n) {
                ch_opt[n] = ch_opt[n] * p->transition_window[n]
                    + ch_target[n] * p->transition_window[p->ola_window_size + n];
            }
        }
    }

    // Next target is one hop ahead of the current optimal.
    p->target_block_index = optimal_index + p->ola_hop_size;
}

void set_output_time(mp_scaletempo2 *p, double output_time)
{
    p->output_time = output_time;
    p->search_block_index = get_search_block_index(p, output_time);
}

void remove_old_input_frames(mp_scaletempo2 *p)
{
    const int earliest_used_index = MPMIN(
        p->target_block_index, p->search_block_index);
    if (earliest_used_index <= 0)
        return;  // Nothing to remove.

    // Remove frames from input and adjust indices accordingly.
    seek_buffer(p, earliest_used_index);
    p->target_block_index -= earliest_used_index;
    p->output_time -= earliest_used_index;
    p->search_block_index -= earliest_used_index;
}

bool run_one_wsola_iteration(mp_scaletempo2 *p, double playback_rate)
{
    if (!can_perform_wsola(p, playback_rate)) {
        return false;
    }

    set_output_time(p, get_updated_time(p, playback_rate));
    remove_old_input_frames(p);

    assert(p->search_block_index + p->search_block_size <= p->input_buffer_frames);

    get_optimal_block(p);

    // Overlap-and-add.
    for (int k = 0; k < p->channels; ++k) {
        float *ch_opt_frame = p->optimal_block[k].data();
        float *ch_output = p->wsola_output[k].data() + p->num_complete_frames;
        if (p->wsola_output_started) {
            for (int n = 0; n < p->ola_hop_size; ++n) {
                ch_output[n] = ch_output[n] * p->ola_window[p->ola_hop_size + n] +
                    ch_opt_frame[n] * p->ola_window[n];
            }

            // Copy the second half to the output.
            memcpy(&ch_output[p->ola_hop_size], &ch_opt_frame[p->ola_hop_size],
                   sizeof(*ch_opt_frame) * static_cast<size_t>(p->ola_hop_size));
        } else {
            // No overlap for the first iteration.
            memcpy(ch_output, ch_opt_frame,
                   sizeof(*ch_opt_frame) * static_cast<size_t>(p->ola_window_size));
        }
    }

    p->num_complete_frames += p->ola_hop_size;
    p->wsola_output_started = true;
    return true;
}

int read_input_buffer(mp_scaletempo2 *p, int dest_size, float *const *dest)
{
    int frames_to_copy = MPMIN(dest_size, p->input_buffer_frames - p->target_block_index);

    if (frames_to_copy <= 0)
        return 0; // There is nothing to read from input buffer; return.

    for (int i = 0; i < p->channels; ++i) {
        memcpy(dest[i], p->input_buffer[i].data() + p->target_block_index,
               static_cast<size_t>(frames_to_copy) * sizeof(float));
    }
    seek_buffer(p, frames_to_copy);
    return frames_to_copy;
}

// Return a "periodic" Hann window. This is the first L samples of an L+1
// Hann window. It is perfect reconstruction for overlap-and-add.
void get_symmetric_hanning_window(int window_length, float *window)
{
    const float scale = 2.0f * static_cast<float>(M_PI) / window_length;
    for (int n = 0; n < window_length; ++n)
        window[n] = 0.5f * (1.0f - cosf(n * scale));
}

} // namespace

void mp_scaletempo2_set_final(mp_scaletempo2 *p)
{
    if (p->input_buffer_final_frames <= 0) {
        p->input_buffer_final_frames = p->input_buffer_frames;
    }
}

int mp_scaletempo2_fill_input_buffer(mp_scaletempo2 *p,
    float *const *planes, int frame_size, double playback_rate)
{
    int needed = frames_needed(p, playback_rate);
    int read = MPMIN(needed, frame_size);
    if (read == 0)
        return 0;

    int last_index = read + p->input_buffer_frames - 1;
    for (int i = 0; i < p->channels; ++i) {
        grow_to_fit(p->input_buffer[i], last_index);
        memcpy(p->input_buffer[i].data() + p->input_buffer_frames,
            planes[i], static_cast<size_t>(read) * sizeof(float));
    }

    p->input_buffer_frames += read;
    return read;
}

int mp_scaletempo2_fill_buffer(mp_scaletempo2 *p,
    float *const *dest, int dest_size, double playback_rate)
{
    if (playback_rate == 0) return 0;

    if (p->input_buffer_final_frames > 0) {
        add_input_buffer_final_silence(p, playback_rate);
    }

    // Optimize the muted case to issue a single clear instead of performing
    // the full crossfade and clearing each crossfaded frame.
    if (playback_rate < p->opts.min_playback_rate
        || (playback_rate > p->opts.max_playback_rate
            && p->opts.max_playback_rate > 0))
    {
        int frames_to_render = MPMIN(dest_size,
            (int)(p->input_buffer_frames / playback_rate));

        // Compute accurate number of frames to actually skip in the source data.
        // Includes the leftover partial frame from last request. However, we can
        // only skip over complete frames, so a partial frame may remain for next
        // time.
        p->muted_partial_frame += frames_to_render * playback_rate;
        int seek_frames = (int)(p->muted_partial_frame);
        for (int i = 0; i < p->channels; ++i) {
            std::memset(dest[i], 0, sizeof(float) * static_cast<size_t>(frames_to_render));
        }
        seek_buffer(p, seek_frames);

        // Determine the partial frame that remains to be skipped for next call. If
        // the user switches back to playing, it may be off time by this partial
        // frame, which would be undetectable. If they subsequently switch to
        // another playback rate that mutes, the code will attempt to line up the
        // frames again.
        p->muted_partial_frame -= seek_frames;
        return frames_to_render;
    }

    int slower_step = (int)ceilf(p->ola_window_size * playback_rate);
    int faster_step = (int)ceilf(p->ola_window_size / playback_rate);

    // Optimize the most common |playback_rate| ~= 1 case to use a single copy
    // instead of copying frame by frame.
    if (p->ola_window_size <= faster_step && slower_step >= p->ola_window_size) {

        if (p->wsola_output_started) {
            p->wsola_output_started = false;

            // sync audio precisely again
            set_output_time(p, p->target_block_index);
            remove_old_input_frames(p);
        }

        return read_input_buffer(p, dest_size, dest);
    }

    int rendered_frames = 0;
    do {
        rendered_frames += write_completed_frames_to(p,
            dest_size - rendered_frames, rendered_frames, dest);
    } while (rendered_frames < dest_size
             && run_one_wsola_iteration(p, playback_rate));
    return rendered_frames;
}

double mp_scaletempo2_get_latency(mp_scaletempo2 *p, double playback_rate)
{
    return p->input_buffer_frames - p->output_time
        - p->input_buffer_added_silence
        + p->num_complete_frames * playback_rate;
}

bool mp_scaletempo2_frames_available(mp_scaletempo2 *p, double playback_rate)
{
    return (p->input_buffer_final_frames > p->target_block_index &&
            p->input_buffer_final_frames > 0)
        || can_perform_wsola(p, playback_rate)
        || p->num_complete_frames > 0;
}

void mp_scaletempo2_reset(mp_scaletempo2 *p)
{
    p->input_buffer_frames = 0;
    p->input_buffer_final_frames = 0;
    p->input_buffer_added_silence = 0;
    p->output_time = 0.0;
    p->search_block_index = 0;
    p->target_block_index = 0;
    p->num_complete_frames = 0;
    p->wsola_output_started = false;
}

void mp_scaletempo2_init(mp_scaletempo2 *p, int channels, int rate)
{
    p->muted_partial_frame = 0;
    p->output_time = 0;
    p->search_block_index = 0;
    p->target_block_index = 0;
    p->num_complete_frames = 0;
    p->wsola_output_started = false;
    p->channels = channels;

    p->samples_per_second = rate;
    p->num_candidate_blocks = (int)(p->opts.wsola_search_interval_ms
        * p->samples_per_second / 1000);
    p->ola_window_size = (int)(p->opts.ola_window_size_ms
        * p->samples_per_second / 1000);
    // Make sure window size in an even number.
    p->ola_window_size += p->ola_window_size & 1;
    p->ola_hop_size = p->ola_window_size / 2;
    // |num_candidate_blocks| / 2 is the offset of the center of the search
    // block to the center of the first (left most) candidate block. The offset
    // of the center of a candidate block to its left most point is
    // |ola_window_size| / 2 - 1. Note that |ola_window_size| is even and in
    // our convention the center belongs to the left half, so we need to subtract
    // one frame to get the correct offset.
    p->search_block_center_offset = p->num_candidate_blocks / 2
        + (p->ola_window_size / 2 - 1);
    p->ola_window.resize(static_cast<size_t>(p->ola_window_size));
    get_symmetric_hanning_window(p->ola_window_size, p->ola_window.data());
    p->transition_window.resize(static_cast<size_t>(p->ola_window_size) * 2);
    get_symmetric_hanning_window(2 * p->ola_window_size, p->transition_window.data());

    p->wsola_output_size = p->ola_window_size + p->ola_hop_size;
    alloc_sample_buffer(p, &p->wsola_output, static_cast<size_t>(p->wsola_output_size));

    // Auxiliary containers.
    alloc_sample_buffer(p, &p->optimal_block, static_cast<size_t>(p->ola_window_size));
    p->search_block_size = p->num_candidate_blocks + (p->ola_window_size - 1);
    alloc_sample_buffer(p, &p->search_block, static_cast<size_t>(p->search_block_size));
    alloc_sample_buffer(p, &p->target_block, static_cast<size_t>(p->ola_window_size));

    p->input_buffer_frames = 0;
    p->input_buffer_final_frames = 0;
    p->input_buffer_added_silence = 0;
    size_t initial_size = 4 * static_cast<size_t>(MPMAX(p->ola_window_size, p->search_block_size));
    alloc_sample_buffer(p, &p->input_buffer, initial_size);

    p->energy_candidate_blocks.resize(
        static_cast<size_t>(p->channels) * static_cast<size_t>(p->num_candidate_blocks));
}

} // namespace wsola
