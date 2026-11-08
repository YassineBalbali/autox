#!/bin/sh
# Runs a command under the Node version CI uses, on Vercel's build image.
#
# Vercel only offers Node majors, and rolls the minor and patch itself, so no setting there can
# say 24.22.1. package.json's `engines` would not even get the major: its range reaches 26, and
# Vercel takes the newest major a range allows. So the install and build commands in
# vercel.json fetch this exact release and put it first on the PATH.
#
# Keep VERSION in step with .github/workflows/frontend.yml; scripts/deploy-config.test.mjs
# fails if they part. SHA256 is the linux-x64 line of
# https://nodejs.org/dist/v<VERSION>/SHASUMS256.txt.
set -eu

VERSION=24.22.1
SHA256=eb6728e77c576a35162c122c45ccadf224d89ff00abc8dae46cc2897c6002d7c

dir="$(cd "$(dirname "$0")/.." && pwd)/.pinned-node"

if [ ! -x "$dir/bin/node" ]; then
  arch="$(uname -m)"
  if [ "$arch" != x86_64 ]; then
    echo "[pinned-node] no checksum for $arch: add one to scripts/pinned-node.sh" >&2
    exit 1
  fi
  mkdir -p "$dir"
  curl -fsSL "https://nodejs.org/dist/v$VERSION/node-v$VERSION-linux-x64.tar.gz" -o "$dir/node.tar.gz"
  echo "$SHA256  $dir/node.tar.gz" | sha256sum -c - >&2
  tar -xzf "$dir/node.tar.gz" -C "$dir" --strip-components=1
  rm "$dir/node.tar.gz"
fi

PATH="$dir/bin:$PATH"
export PATH
echo "[pinned-node] node $(node --version), npm $(npm --version)"
exec "$@"
