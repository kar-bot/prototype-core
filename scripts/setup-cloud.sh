#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
prototype_tools=/workspace/.prototype-tools
mkdir -p "$prototype_tools/downloads" "$prototype_tools/gradle-home"
prototype_jdk="$prototype_tools/jdk-25.0.4.1"
if [[ ! -x "$prototype_jdk/bin/java" ]]; then
    curl --fail --location --retry 2 --proto '=https' --tlsv1.2 \
        https://download.oracle.com/java/25/archive/jdk-25.0.4.1_linux-x64_bin.tar.gz \
        --output "$prototype_tools/downloads/jdk-25.0.4.1.tar.gz"
    (cd "$prototype_tools/downloads" && printf '%s  %s\n' \
        dd7e7f51d23bcffe85f1a439577ce1c0190a33417b34afdb338f9b3897bdeaec \
        jdk-25.0.4.1.tar.gz | sha256sum --check --status)
    tar -xzf "$prototype_tools/downloads/jdk-25.0.4.1.tar.gz" -C "$prototype_tools"
fi
export JAVA_HOME="$prototype_jdk"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$prototype_tools/gradle-home}"
# Java/Gradle do not automatically use the cloud's HTTPS_PROXY. Preserve TLS checking.
# Only proxy host/port metadata is saved; credentials are never read or copied.
python3 - <<'PY'
import os, pathlib, urllib.parse
proxy=urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY',os.environ.get('https_proxy','')))
path=pathlib.Path(os.environ['GRADLE_USER_HOME'])/'gradle.properties'
path.parent.mkdir(parents=True,exist_ok=True)
lines=path.read_text().splitlines() if path.exists() else []
updates={}
if proxy.hostname:
    if proxy.username or proxy.password:
        raise SystemExit('Authenticated proxy requires platform-supported Java authentication; refusing to copy credentials.')
    for scheme in ('https','http'):
        updates[f'systemProp.{scheme}.proxyHost']=proxy.hostname
        updates[f'systemProp.{scheme}.proxyPort']=str(proxy.port or 80)
    updates['systemProp.http.nonProxyHosts']='localhost|127.*|[::1]'
if pathlib.Path('/etc/ssl/certs/java/cacerts').is_file():
    updates['systemProp.javax.net.ssl.trustStore']='/etc/ssl/certs/java/cacerts'
retained=[line for line in lines if line.partition('=')[0].strip() not in updates]
path.write_text('\n'.join(retained+[f'{key}={value}' for key,value in updates.items()])+'\n')
PY
./gradlew --no-daemon test assemble
