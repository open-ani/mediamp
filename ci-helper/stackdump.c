/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

// Prints the native call stack of every thread of a Windows x64 process, read from outside
// with dbghelp. Frames are module!nearest-export+offset, and the offset into the module is
// printed too, so frames of a library built here can be resolved with its symbol table.
//
// The render path matrix runs it against a test JVM that does not exit (buildSrc
// RenderPathMatrix.kt). A native deadlock is only visible this way: jhsdb shows one frame
// per native thread on Windows, and nothing can attach to a process whose loader lock is
// held, because attaching starts a thread in it.
//
// Build: gcc -O1 -o stackdump.exe stackdump.c -ldbghelp
// Usage: stackdump <pid>
#include <windows.h>
#include <tlhelp32.h>
#include <dbghelp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv) {
    if (argc < 2) {
        printf("usage: stackdump <pid>\n");
        return 2;
    }
    DWORD pid = (DWORD) strtoul(argv[1], NULL, 10);
    // Nothing is written while a thread of the target is suspended: with stdout on a pipe
    // read by that very thread, a write would block forever.
    setvbuf(stdout, NULL, _IOFBF, 1 << 20);
    HANDLE process = OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, FALSE, pid);
    if (!process) {
        printf("OpenProcess(%lu) failed: %lu\n", (unsigned long) pid, (unsigned long) GetLastError());
        return 1;
    }
    SymSetOptions(SYMOPT_UNDNAME | SYMOPT_DEFERRED_LOADS | SYMOPT_FAIL_CRITICAL_ERRORS);
    if (!SymInitialize(process, NULL, TRUE)) {
        printf("SymInitialize failed: %lu\n", (unsigned long) GetLastError());
    }
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0);
    if (snapshot == INVALID_HANDLE_VALUE) {
        printf("CreateToolhelp32Snapshot failed: %lu\n", (unsigned long) GetLastError());
        return 1;
    }
    THREADENTRY32 entry;
    memset(&entry, 0, sizeof(entry));
    entry.dwSize = sizeof(entry);
    if (!Thread32First(snapshot, &entry)) {
        printf("Thread32First failed: %lu\n", (unsigned long) GetLastError());
        return 1;
    }
    do {
        if (entry.th32OwnerProcessID != pid) continue;
        DWORD tid = entry.th32ThreadID;
        HANDLE thread = OpenThread(THREAD_GET_CONTEXT | THREAD_SUSPEND_RESUME | THREAD_QUERY_INFORMATION, FALSE, tid);
        if (!thread) {
            printf("--- thread %lu: OpenThread failed: %lu\n", (unsigned long) tid, (unsigned long) GetLastError());
            continue;
        }
        printf("--- thread %lu\n", (unsigned long) tid);
        if (SuspendThread(thread) == (DWORD) -1) {
            printf("  SuspendThread failed: %lu\n", (unsigned long) GetLastError());
            CloseHandle(thread);
            continue;
        }
        CONTEXT context;
        memset(&context, 0, sizeof(context));
        context.ContextFlags = CONTEXT_FULL;
        if (!GetThreadContext(thread, &context)) {
            printf("  GetThreadContext failed: %lu\n", (unsigned long) GetLastError());
        } else {
            STACKFRAME64 frame;
            memset(&frame, 0, sizeof(frame));
            frame.AddrPC.Offset = context.Rip;
            frame.AddrPC.Mode = AddrModeFlat;
            frame.AddrFrame.Offset = context.Rbp;
            frame.AddrFrame.Mode = AddrModeFlat;
            frame.AddrStack.Offset = context.Rsp;
            frame.AddrStack.Mode = AddrModeFlat;
            for (int depth = 0; depth < 48; depth++) {
                if (!StackWalk64(IMAGE_FILE_MACHINE_AMD64, process, thread, &frame, &context, NULL,
                                 SymFunctionTableAccess64, SymGetModuleBase64, NULL)) {
                    break;
                }
                DWORD64 address = frame.AddrPC.Offset;
                if (address == 0) break;
                IMAGEHLP_MODULE64 module;
                memset(&module, 0, sizeof(module));
                module.SizeOfStruct = sizeof(module);
                int has_module = SymGetModuleInfo64(process, address, &module) ? 1 : 0;
                char buffer[sizeof(SYMBOL_INFO) + 512];
                memset(buffer, 0, sizeof(buffer));
                SYMBOL_INFO *symbol = (SYMBOL_INFO *) buffer;
                symbol->SizeOfStruct = sizeof(SYMBOL_INFO);
                symbol->MaxNameLen = 511;
                DWORD64 displacement = 0;
                if (SymFromAddr(process, address, &displacement, symbol)) {
                    printf("  %2d %016llx %s!%s+0x%llx (module+0x%llx)\n", depth, (unsigned long long) address,
                           has_module ? module.ModuleName : "?", symbol->Name, (unsigned long long) displacement,
                           has_module ? (unsigned long long) (address - module.BaseOfImage) : 0ULL);
                } else if (has_module) {
                    printf("  %2d %016llx %s+0x%llx\n", depth, (unsigned long long) address, module.ModuleName,
                           (unsigned long long) (address - module.BaseOfImage));
                } else {
                    printf("  %2d %016llx ?\n", depth, (unsigned long long) address);
                }
            }
        }
        ResumeThread(thread);
        CloseHandle(thread);
        fflush(stdout);
    } while (Thread32Next(snapshot, &entry));
    CloseHandle(snapshot);
    SymCleanup(process);
    CloseHandle(process);
    return 0;
}
