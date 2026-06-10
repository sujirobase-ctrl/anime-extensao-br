 # anime-extensao-br

Extensão Aniyomi / Dantotsu para o site **[Dattebayo BR](https://www.dattebayo-br.com)**, em português brasileiro.

[![CI](https://github.com/ZakiSCzip/anime-extensao-br/actions/workflows/build.yml/badge.svg)](https://github.com/ZakiSCzip/anime-extensao-br/actions/workflows/build.yml)

## Instalar

A maneira mais simples é baixar o `.apk` mais recente do **[Releases](https://github.com/ZakiSCzip/anime-extensao-br/releases)** e instalá-lo no Android (é preciso permitir instalação de fontes desconhecidas).

Também é possível adicionar o repositório da **branch `repo`** em apps compatíveis com o formato Dantotsu:

```
https://raw.githubusercontent.com/ZakiSCzip/anime-extensao-br/repo/index.min.json
```

## O que essa extensão faz

- Lista os animes em destaque na home e novos episódios em "Últimas Atualizações"
- Busca por nome (com fallback automático em sinônimos via AniList se a busca direta falhar)
- Lê a página de cada episódio e extrai as três qualidades disponíveis (`SD 480p`, `HD 720p`, `FULLHD 1080p`)
- Reproduz no player interno do Aniyomi/Dantotsu

## Como buildar localmente

Requisitos: JDK 17+, Android SDK (build-tools 34.x), Gradle wrapper já incluso.

```bash
./gradlew :ext:assembleRelease
```

O APK final aparece em `ext/build/outputs/apk/release/aniyomi-pt.dattebayobr-v14.<X>-release.apk`.

Para assinar um build local, crie um arquivo `keystore.properties` na raiz com:

```
storeFile=/caminho/para/sua.jks
storePassword=...
keyAlias=...
keyPassword=...
```

(este arquivo é ignorado pelo `.gitignore`).

## Como publicar um novo release

1. Atualize `extVersionCode` em `ext/build.gradle.kts`.
2. Faça commit + push pra `main`.
3. Crie e empurre uma tag com o prefixo `v` (ex: `v14.11`):

   ```bash
   git tag v14.11
   git push origin v14.11
   ```

O workflow [`.github/workflows/release.yml`](.github/workflows/release.yml) vai buildar, assinar e anexar o APK ao release automaticamente.  
Na sequência, o workflow [`.github/workflows/generate-repo.yml`](.github/workflows/generate-repo.yml) atualiza o índice na branch `repo`.

## Estrutura

```
.
├── ext/                                       # Módulo Gradle da extensão
│   ├── AndroidManifest.xml                    # Manifest dinâmico (placeholders preenchidos pelo build)
│   ├── build.gradle.kts                       # Define versão / assinatura / deps
│   ├── res/                                   # Ícones do launcher
│   └── src/main/kotlin/eu/kanade/tachiyomi/animeextension/pt/dattebayobr/
│       └── DattebayoBR.kt                     # Implementação da extensão
├── buildSrc/                                  # Plugin Gradle convencional do Aniyomi
├── gradle/libs.versions.toml                  # Catálogo de versões (Aniyomi-lib, OkHttp, JSoup…)
├── .github/
│   └── workflows/
│       ├── build.yml                          # Build em todo push / PR
│       ├── release.yml                        # Release em tag `v*.*`
│       └── generate-repo.yml                  # Gera índice na branch `repo`
└── .gitattributes                             # Normalização de line endings
```

## Licença

[Apache 2.0](LICENSE)
