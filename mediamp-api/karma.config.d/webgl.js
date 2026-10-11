const testResources = require("path").resolve(
    config.basePath, "../../../../mediamp-api/src/wasmJsTest/resources"
).replace(/\\/g, "/");
config.files.push({
    pattern: testResources + "/**/*",
    included: false,
    served: true,
    watched: false
});
config.proxies["/test-resources/"] = encodeURI("/absolute" + testResources + "/");

// Browser rendering tests need WebGL2 even on GPU-less CI machines.
config.set({
    customLaunchers: {
        ChromeHeadlessWebGL: {
            base: "ChromeHeadless",
            flags: ["--enable-unsafe-swiftshader"]
        }
    },
    browsers: ["ChromeHeadlessWebGL"],
    browserNoActivityTimeout: 120000,
    client: { mocha: { timeout: 60000 } }
});
