// Checks what the page ships against Chromium 76's feature level, without a Chromium 76:
//  1. every script parses as ECMAScript 2019 (Chromium 76 has all of it, and little after it);
//  2. no script names an API that arrived after Chromium 76;
//  3. the WebAssembly validates with only the post-MVP features Chromium 76 has turned on;
//  4. no function crossing between JavaScript and WebAssembly takes or returns a 64-bit integer,
//     which Chromium only passes as a BigInt from version 85.
// Run: npm install && npm run check
const fs = require("fs");
const path = require("path");
const acorn = require("acorn");

const app = path.join(__dirname, "..", "app");
let failed = false;
function fail(message) { failed = true; console.log("FAIL  " + message); }
function pass(message) { console.log("ok    " + message); }

// APIs newer than Chromium 76, each with the version that first had it.
const NEWER_APIS = [
  [/\.replaceAll\s*\(/, "String.prototype.replaceAll (85)"],
  [/\.at\s*\(/, "Array.prototype.at (92)"],
  [/Object\.hasOwn\b/, "Object.hasOwn (93)"],
  [/\bstructuredClone\b/, "structuredClone (98)"],
  [/\bWeakRef\b/, "WeakRef (84)"],
  [/\bFinalizationRegistry\b/, "FinalizationRegistry (84)"],
  [/Promise\.any\b/, "Promise.any (85)"],
  [/\bAggregateError\b/, "AggregateError (85)"],
  [/\.findLast(Index)?\s*\(/, "Array.prototype.findLast (97)"],
  [/\bgetDirectory\s*\(/, "navigator.storage.getDirectory, the Origin Private File System (86)"],
  [/\bcreateSyncAccessHandle\b/, "createSyncAccessHandle (102)"],
  [/\bBigInt64Array\b|\bBigUint64Array\b|\bBigInt\s*\(/, "BigInt across the WebAssembly boundary (85)"],
  [/\bqueueMicrotask\b|\bglobalThis\b/, null], // 71: fine, listed so the reader sees they were thought of
];

const scripts = [];
for (const file of fs.readdirSync(path.join(app, "js")).sort()) if (file.endsWith(".js")) scripts.push(path.join("js", file));
const html = fs.readFileSync(path.join(app, "index.html"), "utf8");
const inline = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
if (inline.length) fail("index.html has an inline script, which this check doesn't read");
for (const src of [...html.matchAll(/<script[^>]*\bsrc="([^"]+)"/g)].map((m) => m[1])) {
  if (!src.startsWith("$WEBAPIS") && !scripts.includes(src)) fail("index.html loads " + src + ", which isn't in app/js");
}

for (const script of scripts) {
  const source = fs.readFileSync(path.join(app, script), "utf8");
  try {
    acorn.parse(source, { ecmaVersion: 2019, sourceType: "script" });
    pass(script + " parses as ECMAScript 2019");
  } catch (e) {
    fail(script + " is not ECMAScript 2019: " + e.message);
  }
  // The base64 WebAssembly is one long string; names can't be read out of it.
  const code = source.replace(/"[A-Za-z0-9+\/=]{1000,}"/g, '""');
  for (const [pattern, name] of NEWER_APIS) if (name && pattern.test(code)) fail(script + " uses " + name);
}

const wasmSource = fs.readFileSync(path.join(app, "js", "sqlite3-wasm.js"), "utf8");
const wasm = Buffer.from(/"([A-Za-z0-9+\/=]+)"/.exec(wasmSource)[1], "base64");

// Every feature off but the four Chromium 76 has on by default: mutable globals (74),
// sign-extension (74), non-trapping float-to-int (75) and bulk memory (75).
const ALL_OFF = {
  mutable_globals: false, sat_float_to_int: false, sign_extension: false, bulk_memory: false,
  simd: false, threads: false, multi_value: false, tail_call: false, reference_types: false, exceptions: false,
  gc: false, memory64: false, extended_const: false, relaxed_simd: false, function_references: false, annotations: false,
};
const CHROMIUM_76 = Object.assign({}, ALL_OFF, { mutable_globals: true, sat_float_to_int: true, sign_extension: true, bulk_memory: true });

require("wabt")().then((wabt) => {
  function validates(features) {
    try {
      const module = wabt.readWasm(wasm, Object.assign({ readDebugNames: false }, features));
      module.validate();
      return null;
    } catch (e) {
      return String(e.message).split("\n").filter(Boolean).slice(0, 2).join(" ");
    }
  }
  const at76 = validates(CHROMIUM_76);
  if (at76) fail("the WebAssembly needs a feature Chromium 76 lacks: " + at76);
  else pass("the WebAssembly (" + wasm.length + " bytes) validates with only Chromium 76's features on");
  // Which of the four it really uses, for the record.
  for (const feature of ["mutable_globals", "sat_float_to_int", "sign_extension", "bulk_memory"]) {
    const without = validates(Object.assign({}, CHROMIUM_76, { [feature]: false }));
    console.log("      " + feature + ": " + (without ? "used" : "not used"));
  }

  // The boundary: imports and exports, read from the text form.
  const text = wabt.readWasm(wasm, Object.assign({ readDebugNames: false }, CHROMIUM_76));
  const wat = text.toText({ foldExprs: false, inlineExport: false });
  const types = {};
  for (const m of wat.matchAll(/^\s*\(type \(;(\d+);\) \(func([^\n]*)\)\)$/gm)) types[m[1]] = m[2];
  const funcType = {};
  let index = 0;
  const boundary = [];
  for (const m of wat.matchAll(/^\s*\(import "([^"]*)" "([^"]*)" \(func \(;\d+;\) \(type (\d+)\)\)\)$/gm)) {
    funcType[index++] = m[3];
    boundary.push(["import " + m[1] + "." + m[2], m[3]]);
  }
  for (const m of wat.matchAll(/^\s*\(func \(;(\d+);\) \(type (\d+)\)/gm)) funcType[m[1]] = m[2];
  for (const m of wat.matchAll(/^\s*\(export "([^"]*)" \(func (\d+)\)\)$/gm)) boundary.push(["export " + m[1], funcType[m[2]]]);
  const wide = boundary.filter(([, type]) => /\bi64\b/.test(types[type] || ""));
  if (!boundary.length || boundary.some(([, type]) => types[type] === undefined)) fail("could not read the WebAssembly's imports and exports");
  else if (wide.length) fail("64-bit integers cross the boundary in: " + wide.map(([name]) => name).join(", "));
  else pass("none of the " + boundary.length + " imported and exported functions passes a 64-bit integer");

  console.log(failed ? "\nNot within Chromium 76." : "\nWithin Chromium 76, as far as syntax and WebAssembly features tell.");
  process.exit(failed ? 1 : 0);
});
