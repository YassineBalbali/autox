// The Android version of a release, from its tag.
//
//   node tool/release_version.mjs mobile-v1.2.3
//   name=1.2.3
//   code=10203
//
// versionName is the tag's x.y.z. versionCode is major × 10000 + minor × 100 + patch, so it
// grows with every release and the same tag always gives the same number: Android refuses to
// update an installed app with a lower code. Hence minor and patch stay below 100, and the
// version is at least 0.0.1 (a code of 0 is not allowed). A tag that does not fit is refused:
// the release job fails before building anything.
//
// The lines are in GitHub Actions' `$GITHUB_OUTPUT` format.

const tag = process.argv[2] ?? '';
const match = /^mobile-v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.exec(tag);
if (!match) {
  console.error(`"${tag}" is not a release tag: mobile-v<major>.<minor>.<patch>, e.g. mobile-v1.2.3.`);
  process.exit(1);
}
const [major, minor, patch] = match.slice(1).map(Number);
if (minor > 99 || patch > 99) {
  console.error(`${tag}: minor and patch must be below 100, or the version code would collide.`);
  process.exit(1);
}
const code = major * 10000 + minor * 100 + patch;
// Google Play's ceiling, and Android's: a code is a positive 32-bit integer, at most 2100000000.
if (code < 1 || code > 2100000000) {
  console.error(`${tag}: the version code ${code} is out of range.`);
  process.exit(1);
}
console.log(`name=${major}.${minor}.${patch}`);
console.log(`code=${code}`);
