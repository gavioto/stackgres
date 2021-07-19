#!/bin/sh

set -e

SHELL="$(readlink /proc/$$/exe)"
if [ "$(basename "$SHELL")" = busybox ]
then
  SHELL=sh
fi
SHELL_XTRACE="$(! echo $- | grep -q x || echo "-x")"

cd "$(dirname "$0")"

mkdir -p target
rm -rf target/public
cp -a dist target/public

mkdir -p target/public/info
cp ../api-web/target/swagger-merged.json target/public/info/sg-tooltips.json

mkdir -p target/public/info
# Export SG version to show on the UI
grep '<artifactId>stackgres-parent</artifactId>' "../pom.xml" -A 2 -B 2 \
  | sed -n 's/^.*<version>\([^<]\+\)<\/version>.*$/\1/p' \
  | xargs -I % echo '{"version":"%"}' > target/public/info/sg-info.json
