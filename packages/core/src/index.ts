export * from "./categories.js";
export * from "./types.js";
export * from "./matcher.js";
export * from "./settings.js";
export * from "./rules.js";
export * from "./scramble.js";
export * from "./feed.js";
export * from "./subscriptions.js";
export * from "./blur.js";
export * from "./advisory.js";

// No `blocklists` module. It used to statically `import` the five JSON files so
// that `bundledDomains()` could return them — but nothing ever called it, and
// with the lists now uncapped that import meant anything depending on this
// package pulled 4.5M plaintext domains into its build. That both exhausted
// Node's heap and risked embedding a readable copy of the list in the shipped
// extension, which is exactly what this project is arranged to prevent.
//
// The lists are read from disk by the build scripts and bundled per-platform in
// each app's own encoded form. See CLAUDE.md.
