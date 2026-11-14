// Generates what the app compiles against, from the files the web compiles against:
//
//   node tool/generate_api.mjs
//
//   - packages/autox_api, the Dart API client, from frontend/openapi.json (the snapshot the web
//     client is generated from and that the backend's OpenApiSnapshotTest keeps current);
//   - lib/core/labels/labels.g.dart, the French words, from frontend/labels.fr.json.
//
// Neither output is edited or committed; CI runs this before anything else. It FAILS, rather
// than producing something that half works, when:
//   - the generator version differs from the web's (frontend/openapitools.json);
//   - the spec does not validate;
//   - the generator warns about anything not on the known list below (a construct it does not
//     support, a name it had to change): that is the spec and the generator disagreeing;
//   - one of the fixes applied to its output no longer applies;
//   - the generated package does not compile on its own.
//
// Node rather than Dart because it runs before `flutter pub get` can: the root pubspec lists
// the generated package as a workspace member, so pub cannot resolve until it exists.

import { createHash } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const MOBILE = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const REPO = resolve(MOBILE, '..');
const SPEC = join(REPO, 'frontend', 'openapi.json');
const WEB_GENERATOR_CONFIG = join(REPO, 'frontend', 'openapitools.json');
const LABELS = join(REPO, 'frontend', 'labels.fr.json');
const OUT = join(MOBILE, 'packages', 'autox_api');
const LABELS_OUT = join(MOBILE, 'lib', 'core', 'labels', 'labels.g.dart');

// The web's generator version, checked below against frontend/openapitools.json: one generator
// version, one reading of the spec. The SHA-256 is of the jar on Maven Central, whose published
// SHA-1 (b96b148f…) it was cross-checked against. Moving the version means a new checksum.
const GENERATOR_VERSION = '7.26.0';
const GENERATOR_SHA256 = '1760050094997b9cc790cc1350be095f15da8c08a996b184d3eadc4ccda5e35e';
const GENERATOR_URL =
  'https://repo1.maven.org/maven2/org/openapitools/openapi-generator-cli/' +
  `${GENERATOR_VERSION}/openapi-generator-cli-${GENERATOR_VERSION}.jar`;
const JAR = join(MOBILE, '.dart_tool', 'openapi-generator', `${GENERATOR_VERSION}.jar`);

// Generator warnings that are understood and harmless. Anything else fails the run: add a line
// here only once the warning has been read and the generated code checked.
const KNOWN_WARNINGS = [
  // The spec is OpenAPI 3.1; the generator reads it correctly for everything this API uses.
  /OpenAPI 3\.1 support is still in beta/,
  // An admin endpoint named "hide" becomes callHide in Dart. The app never calls it.
  /hide \(reserved word\) cannot be used as method name\. Renamed to callHide/,
];

const isWindows = process.platform === 'win32';

function fail(message) {
  console.error(`\ngenerate_api: ${message}`);
  process.exit(1);
}

function run(command, args, cwd = MOBILE) {
  // On Windows flutter and dart are .bat files, which only cmd.exe can start. The arguments
  // are this script's own constants, never input.
  if (isWindows) {
    execFileSync('cmd.exe', ['/d', '/c', command, ...args], { cwd, stdio: 'inherit' });
  } else {
    execFileSync(command, args, { cwd, stdio: 'inherit' });
  }
}

/** Runs the generator jar, prints its output, and returns it without colour codes. */
function java(args) {
  const result = spawnSync('java', ['-jar', JAR, ...args], { cwd: MOBILE, encoding: 'utf8' });
  const output = `${result.stdout ?? ''}${result.stderr ?? ''}`.replace(/\x1b\[[0-9;]*m/g, '');
  process.stdout.write(output);
  if (result.error) {
    fail(`could not start java: ${result.error.message}`);
  }
  if (result.status !== 0) {
    fail(`openapi-generator ${args[0]} exited with ${result.status}`);
  }
  return output;
}

function sha256(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}

function checkGeneratorVersion() {
  const web = JSON.parse(readFileSync(WEB_GENERATOR_CONFIG, 'utf8'))['generator-cli']?.version;
  if (web !== GENERATOR_VERSION) {
    fail(
      `the web generates with openapi-generator ${web} (frontend/openapitools.json), this script with ` +
        `${GENERATOR_VERSION}. Move both together: GENERATOR_VERSION and GENERATOR_SHA256 here.`,
    );
  }
}

async function ensureJar() {
  if (existsSync(JAR) && sha256(JAR) === GENERATOR_SHA256) {
    return;
  }
  console.log(`Downloading openapi-generator ${GENERATOR_VERSION}…`);
  const response = await fetch(GENERATOR_URL);
  if (!response.ok) {
    fail(`download failed: ${response.status} ${GENERATOR_URL}`);
  }
  const bytes = Buffer.from(await response.arrayBuffer());
  const actual = createHash('sha256').update(bytes).digest('hex');
  if (actual !== GENERATOR_SHA256) {
    fail(`openapi-generator checksum mismatch: expected ${GENERATOR_SHA256}, got ${actual}`);
  }
  mkdirSync(dirname(JAR), { recursive: true });
  writeFileSync(JAR, bytes);
}

function generate() {
  java(['validate', '-i', SPEC]);

  // Retries: on Windows an editor or file watcher holding the folder briefly is common.
  rmSync(OUT, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
  const output = java([
    'generate',
    '-i', SPEC,
    '-g', 'dart-dio',
    '-o', OUT,
    '--additional-properties',
    [
      'pubName=autox_api',
      'serializationLibrary=json_serializable',
      'dateLibrary=core',
      // An enum value the app does not know yet (a fuel type added on the backend) parses as
      // `unknownDefaultOpenApi` instead of failing the whole response.
      'enumUnknownDefaultCase=true',
      // No copyWith on the models: its builder, copy_with_extension_gen, does not support the
      // build 5 that the pinned build_runner needs, and the app builds new values rather than
      // copying API objects.
      'skipCopyWith=true',
    ].join(','),
    // Docs and stub tests for 157 schemas, none of which anybody would read or keep.
    '--global-property', 'apiTests=false,modelTests=false,apiDocs=false,modelDocs=false',
  ]);

  const unknown = output
    .split(/\r?\n/)
    .filter((line) => /\b(WARN|ERROR)\b/.test(line))
    .filter((line) => !KNOWN_WARNINGS.some((known) => known.test(line)));
  if (unknown.length > 0) {
    fail(
      'the generator reported something new about the spec. Read it, check the generated code, ' +
        'then fix the spec or add the line to KNOWN_WARNINGS:\n  ' +
        unknown.join('\n  '),
    );
  }
}

// The generated pubspec pins nothing useful and declares a standalone package. Replaced by
// one that joins the app's pub workspace with `any` constraints: versions are then decided by
// the app's pubspec and its committed pubspec.lock, the single lock for everything.
function rewritePubspec() {
  writeFileSync(
    join(OUT, 'pubspec.yaml'),
    `# Generated by tool/generate_api.mjs. Do not edit.
name: autox_api
description: AutoX API client, generated from frontend/openapi.json.
publish_to: none
version: 1.0.0
resolution: workspace

environment:
  sdk: ^3.14.0

dependencies:
  dio: any
  # A floor, not a pin: json_serializable refuses to build against a constraint that allows
  # an annotation package older than itself.
  json_annotation: ^4.12.0

dev_dependencies:
  build_runner: any
  json_serializable: any
`,
  );
}

// The generator's build.yaml sets `disallow_unrecognized_keys: true`: a field added to any
// response would then throw while parsing it, and an API release would break every installed
// app. The backend adds fields without a version bump (the web ignores them), so this must be
// off. Checked, rather than assumed: if the option ever disappears the replace would silently
// do nothing.
function relaxBuildYaml() {
  const path = join(OUT, 'build.yaml');
  const original = readFileSync(path, 'utf8');
  const relaxed = original.replace('disallow_unrecognized_keys: true', 'disallow_unrecognized_keys: false');
  if (!/disallow_unrecognized_keys: false/.test(relaxed)) {
    fail('build.yaml: could not switch off disallow_unrecognized_keys');
  }
  writeFileSync(path, relaxed);
}

// dart-dio writes an enum parameter's default unqualified — `SearchSort? sort = NEWEST,` —
// which does not compile. Qualified here with the parameter's own type. A later generator that
// gets it right simply leaves nothing to replace; the compile check below catches the rest.
function qualifyEnumDefaults() {
  const dir = join(OUT, 'lib', 'src', 'api');
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    const source = readFileSync(path, 'utf8');
    const fixed = source.replace(/\b([A-Z]\w*)\? (\w+) = ([A-Z][A-Z0-9_]*),/g, '$1? $2 = $1.$3,');
    if (fixed !== source) {
      writeFileSync(path, fixed);
    }
  }
}

// ---- labels ---------------------------------------------------------------------------------

const dartString = (text) =>
  `'${text.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/\$/g, '\\$').replace(/\n/g, '\\n')}'`;

/** FuelType → fuelType */
const lowerFirst = (name) => name.charAt(0).toLowerCase() + name.slice(1);

/**
 * frontend/labels.fr.json as Dart. Each enum becomes an exhaustive `switch` over the generated
 * enum, so a value missing from the JSON, or one the API no longer has, is a compile error —
 * the same guarantee the web gets from typing each map as a `Record`.
 */
function generateLabels() {
  const labels = JSON.parse(readFileSync(LABELS, 'utf8'));
  const out = [
    '// GENERATED by tool/generate_api.mjs from frontend/labels.fr.json. Do not edit: change the',
    '// JSON, which the web reads too, and run the script again.',
    '',
    "import 'package:autox_api/autox_api.dart';",
    '',
  ];

  for (const [enumName, words] of Object.entries(labels.enums)) {
    const name = lowerFirst(enumName);
    out.push(`/// ${enumName} in French. An unknown value (newer than this build) has no word: ''.`);
    out.push(`String ${name}Label(${enumName} value) => switch (value) {`);
    for (const [value, word] of Object.entries(words)) {
      out.push(`  ${enumName}.${value} => ${dartString(word)},`);
    }
    out.push(`  ${enumName}.unknownDefaultOpenApi => '',`);
    out.push('};', '');
    out.push(`/// ${enumName} in French, by wire value and in the JSON's order (the display order).`);
    out.push(`const Map<String, String> ${name}Labels = {`);
    for (const [value, word] of Object.entries(words)) {
      out.push(`  ${dartString(value)}: ${dartString(word)},`);
    }
    out.push('};', '');
  }

  for (const [menu, options] of Object.entries(labels.sortOptions)) {
    out.push(`/// The ${menu} sort menu, in order.`);
    out.push(`const List<(SearchSort, String)> ${menu}SortOptions = [`);
    for (const { value, label } of options) {
      out.push(`  (SearchSort.${value}, ${dartString(label)}),`);
    }
    out.push('];', '');
  }

  out.push("/// The Crit'Air filter's choices: the highest class wanted.");
  out.push('const List<(int, String)> critAirMaxOptions = [');
  for (const { value, label } of labels.critAirMaxOptions) {
    out.push(`  (${Number(value)}, ${dartString(label)}),`);
  }
  out.push('];', '');

  out.push('/// The request fields a validation failure can name, in French.');
  out.push('const Map<String, String> fieldLabels = {');
  for (const [field, label] of Object.entries(labels.fields)) {
    out.push(`  ${dartString(field)}: ${dartString(label)},`);
  }
  out.push('};');

  mkdirSync(dirname(LABELS_OUT), { recursive: true });
  writeFileSync(LABELS_OUT, out.join('\n') + '\n');
}

checkGeneratorVersion();
await ensureJar();
generate();
rewritePubspec();
relaxBuildYaml();
qualifyEnumDefaults();
run('flutter', ['pub', 'get']);
run('dart', ['run', 'build_runner', 'build', '--delete-conflicting-outputs'], OUT);
// Errors only: the generated code carries warnings (unused imports) that are not ours to fix.
try {
  run('dart', ['analyze', '--no-fatal-warnings'], OUT);
} catch {
  fail('the generated client does not compile (see the errors above).');
}
generateLabels();
run('dart', ['format', '--page-width', '120', LABELS_OUT]);
console.log('API client generated in packages/autox_api, labels in lib/core/labels/labels.g.dart.');
