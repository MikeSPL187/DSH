# Образ Linux для DSH: Debian 13 (trixie) + Node 24 + агент DSH + bridge-плагин.
#
# Всё, что попадает в образ, закреплено содержимым, а не именем:
#   - базовый образ — по digest;
#   - агент — наша сборка из исходников `agent/` (форк, pnpm по `agent/pnpm-lock.yaml`), сторонние
#     зависимости — `infra/rootfs/agent/package-lock.json` (integrity каждого пакета), `npm ci`;
#   - pnpm — tarball с integrity из реестра;
#   - bridge-плагин — папка `build/bridge-plugin`, которую CI кладёт сюда только после проверки
#     contentSha256 из `app/src/main/assets/bridge-plugin.json`; его зависимости — `profile/pnpm-lock.yaml`;
#   - пакеты Debian — подписи apt (ключи из закреплённого базового образа), список версий — в SBOM.
# Android-поведение агента — в его исходниках (переменная DSH_ANDROID=1), в манифесте `androidPatched: true`:
# приложение не переписывает файлы агента своими копиями.
ARG NODE_IMAGE=node:24.22.1-trixie-slim@sha256:43b9ba1142b8dcf33fa8f922ca1be85714adc444e0586c7cf7726daaeabc1bea
ARG DSH_VERSION=0.2.1-rc.2
# Коммит upstream, от которого отходит agent/ (версия клиента вшивает его, см. third_party/README.md).
ARG DSH_SOURCE_COMMIT=687f282e8625c6b8d11ff58211a90524be2e4317
ARG BUILD_PNPM_VERSION=11.28.5
ARG BUILD_PNPM_INTEGRITY=sha512-SEVj0wtMKtJ8tIqpT/KzAtvOvI/2vyiBbCWJiG4UbOf3bzVPO8GkOOb16XaMMCqgpvet2CMpA81M+BQmy8FNng==
ARG PNPM_VERSION=10.34.9
ARG PNPM_INTEGRITY=sha512-9JtajqD97bRLs8DB1ldQkqU8KVDZW3rRXfEfF5dId6LeOgHAfpNOmyjOcW5RwJqUlixzBtCb4uHksYqMh2L9Mg==
ARG BRIDGE_VERSION=2.0.7

# Скачать tarball из реестра npm и сверить integrity; иначе сборка падает.
FROM ${NODE_IMAGE} AS fetch
ARG PNPM_VERSION
ARG PNPM_INTEGRITY
ARG BUILD_PNPM_VERSION
ARG BUILD_PNPM_INTEGRITY
WORKDIR /fetch
COPY fetch-npm.js /fetch/fetch-npm.js
RUN node fetch-npm.js "https://registry.npmjs.org/pnpm/-/pnpm-${PNPM_VERSION}.tgz" "${PNPM_INTEGRITY}" pnpm.tgz \
  && node fetch-npm.js "https://registry.npmjs.org/pnpm/-/pnpm-${BUILD_PNPM_VERSION}.tgz" "${BUILD_PNPM_INTEGRITY}" pnpm-build.tgz

# Агент из исходников (контекст `agent-src` = каталог agent/ репозитория): зависимости сборки строго по
# agent/pnpm-lock.yaml, сборка — скриптами самого репозитория, итог — дерево установки `@deepseek-ai/dsh`.
FROM ${NODE_IMAGE} AS agent-build
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
  && apt-get install -y --no-install-recommends git python3 ca-certificates gcc libc6-dev make \
  && rm -rf /var/lib/apt/lists/*
COPY --from=fetch /fetch/pnpm-build.tgz /tmp/pnpm-build.tgz
RUN npm install -g --no-audit --no-fund --ignore-scripts /tmp/pnpm-build.tgz && rm /tmp/pnpm-build.tgz
COPY --from=agent-src . /src
WORKDIR /src
ARG DSH_SOURCE_COMMIT
# Каждый пакет `@deepseek-ai/*` — архивом `pnpm pack`, как при публикации в npm (те же `files`).
RUN pnpm install --frozen-lockfile \
  && DSH_CLIENT_COMMIT_HASH="$DSH_SOURCE_COMMIT" pnpm run build:artifacts \
  && mkdir -p /out/packs \
  && pnpm -r --filter '@deepseek-ai/*' --filter '!@deepseek-ai/node-addon-system-*' \
       exec sh -c 'pnpm pack --pack-destination /out/packs >/dev/null' \
  && ls /out/packs | wc -l

# Агент в образе: сторонние зависимости — `npm ci` по нашему package-lock, затем каждый пакет
# `@deepseek-ai/*` и сам `@deepseek-ai/dsh` заменяются содержимым нашего архива той же версии (пакета без
# нашего архива быть не может — сборка падает), опубликованные package.json и вложенные node_modules
# (свои версии зависимостей по lockfile) остаются. Исключение — `libreoffice-kit*`: готовые движки
# из отдельного репозитория DeepSeek, в исходниках agent/ их нет, и `node-addon-system-<платформа>` —
# статический лаунчер Landlock и flock-аддоны (полная нативная сборка с musl пока не в нашем CI);
# оба ставятся из npm по integrity lockfile.
# Здесь же — bundle входа Host (esbuild — dev-зависимость того же lockfile): ~1200 открытий файлов
# модулей при запуске сводятся к одному.
FROM ${NODE_IMAGE} AS agent
ARG AGENT_DIR=/usr/local/lib/node_modules/@deepseek-ai/dsh
COPY --from=agent-build /out/packs /out/packs
COPY agent/package.json agent/package-lock.json /tmp/agent/
RUN set -eu; \
    mkdir -p "$AGENT_DIR"; \
    cp /tmp/agent/package.json /tmp/agent/package-lock.json "$AGENT_DIR/"; \
    cd "$AGENT_DIR"; \
    npm ci --no-audit --no-fund; \
    overlay() { \
      pack="/out/packs/$(node -p 'const p = require(process.argv[1]); p.name.slice(1).replace("/", "-") + "-" + p.version + ".tgz"' "$1/package.json")"; \
      [ -f "$pack" ] || { echo "no source build: $pack" >&2; exit 1; }; \
      find "$1" -mindepth 1 -maxdepth 1 ! -name node_modules ! -name package.json ! -name package-lock.json -exec rm -rf {} +; \
      tar -xzf "$pack" -C "$1" --strip-components=1 --no-same-owner --exclude=package/package.json; \
    }; \
    overlay "$AGENT_DIR"; \
    count=1; \
    for installed in $(find node_modules -type d -path '*/node_modules/@deepseek-ai/*' -prune); do \
      case "$(basename "$installed")" in libreoffice-kit*|node-addon-system-*) continue ;; esac; \
      overlay "$installed"; count=$((count + 1)); \
    done; \
    echo "replaced $count @deepseek-ai packages with the source build"; \
    rm -rf /out
WORKDIR /usr/local/lib/node_modules/@deepseek-ai/dsh/lib
RUN ../node_modules/.bin/esbuild bin.js --bundle --platform=node --format=esm --target=node24 \
      --outfile=host.bundle.mjs --external:*.node --external:node:* --log-level=warning \
  && ls -lh host.bundle.mjs
# В образ — без dev-зависимостей (esbuild и прочее только для сборки).
RUN cd .. && npm prune --omit=dev --no-audit --no-fund

FROM ${NODE_IMAGE}
ARG DSH_VERSION
ARG BRIDGE_VERSION
ENV DEBIAN_FRONTEND=noninteractive
ENV PNPM_HOME=/usr/local
ENV PATH=$PNPM_HOME/bin:$PATH

# 1. Системные инструменты: Python 3, Git, сертификаты, patch.
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    git \
    ca-certificates \
    patch \
  && rm -rf /var/lib/apt/lists/* /usr/share/doc /usr/share/man /usr/share/info /usr/share/lintian

# 2. Агент (с патчами и bundle) и pnpm из проверенного tarball.
COPY --from=agent /usr/local/lib/node_modules/@deepseek-ai/dsh /usr/local/lib/node_modules/@deepseek-ai/dsh
COPY --from=fetch /fetch/pnpm.tgz /tmp/pnpm.tgz
RUN ln -s ../lib/node_modules/@deepseek-ai/dsh/lib/bin.js /usr/local/bin/dsh \
  && npm install -g --no-audit --no-fund --ignore-scripts /tmp/pnpm.tgz \
  && rm /tmp/pnpm.tgz \
  && mkdir -p /opt/dsh/bin \
  && printf '#!/bin/sh\nexec /usr/local/bin/dsh "$@"\n' > /opt/dsh/bin/dsh \
  && chmod +x /opt/dsh/bin/dsh

# 3. Профиль web с bridge-плагином: зависимости — строго по lockfile профиля.
COPY build/bridge-plugin /opt/dsh/bridge-plugin
COPY profile/ /root/.dsh/profiles/web/
RUN dsh plugin --profile web install --frozen-lockfile --ignore-scripts \
  && node -e " \
       const fs = require('node:fs'); \
       const base = '/root/.dsh/profiles/web/node_modules/dsh-mobile-bridge-plugin'; \
       const pkg = JSON.parse(fs.readFileSync(base + '/package.json', 'utf8')); \
       if (pkg.version !== process.argv[1]) throw new Error('bridge version ' + pkg.version + ' != ' + process.argv[1]); \
       const patch = pkg.dsh && pkg.dsh.bundle && pkg.dsh.bundle.patch; \
       if (!patch || !fs.existsSync(base + '/' + patch)) throw new Error('bridge bundle patch missing: ' + patch); \
       if (!fs.existsSync(base + '/' + pkg.main)) throw new Error('bridge entry missing: ' + pkg.main); \
       console.log('bridge ' + pkg.version + ' installed with patch ' + patch); \
     " "${BRIDGE_VERSION}"

# 4. Под proot определение libc у node-addon-require-builtin может ошибиться и искать -musl:
# образ на glibc, поэтому имя -musl ведёт на тот же пакет -gnu. npm ставит и сам пакет -musl (в нём нет
# поля libc), но его бинарник в glibc не загрузится — он заменяется ссылкой.
RUN set -eux; \
    modules=/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules; \
    arch="$(node -p 'process.arch')"; \
    for gnu in "$modules"/*-linux-"$arch"-gnu; do \
      [ -d "$gnu" ] || continue; \
      musl="${gnu%-gnu}-musl"; \
      rm -rf "$musl"; \
      ln -s "$(basename "$gnu")" "$musl"; \
    done; \
    node -e " \
      const modules = process.argv[1]; \
      for (const suffix of ['gnu', 'musl']) { \
        const addon = require(modules + '/node-addon-require-builtin-linux-' + process.arch + '-' + suffix); \
        const loader = addon.requireBuiltin('internal/modules/esm/loader'); \
        if (typeof loader.getOrInitializeCascadedLoader !== 'function') throw new Error(suffix + ': unusable ESM loader'); \
      } \
    " "$modules"

# 5. SBOM: версии пакетов Debian и npm внутри образа — в /opt/dsh/sbom, CI прикладывает их к релизу.
RUN mkdir -p /opt/dsh/sbom \
  && dpkg-query -W -f '${Package}\t${Version}\t${Architecture}\n' > /opt/dsh/sbom/dpkg.tsv \
  && cp /usr/local/lib/node_modules/@deepseek-ai/dsh/package-lock.json /opt/dsh/sbom/agent-package-lock.json \
  && cp /root/.dsh/profiles/web/pnpm-lock.yaml /opt/dsh/sbom/profile-pnpm-lock.yaml \
  && node -e "console.log(process.versions.node)" > /opt/dsh/sbom/node-version.txt \
  && pnpm --version > /opt/dsh/sbom/pnpm-version.txt

# 6. Проверка: агент запускается, npm в образ не попадает (ставить пакеты — только через pnpm профиля).
RUN node /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js --version \
  && rm -rf /root/.npm /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack \
       /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack
ENV BRIDGE_VERSION=${BRIDGE_VERSION}
