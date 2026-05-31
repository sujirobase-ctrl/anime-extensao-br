# anime-extensao-br

Extensão Aniyomi / Dantotsu para o site **[Dattebayo BR](https://www.dattebayo-br.com)**, em português brasileiro.

## Instalar

A maneira mais simples é baixar o `.apk` mais recente do menu **[Releases](../../releases)** e instalá-lo no Android (é preciso permitir instalação de fontes desconhecidas).

Funciona tanto no [Aniyomi](https://github.com/aniyomiorg/aniyomi) quanto em forks do [Dantotsu](https://github.com/rebelonion/Dantotsu) que carregam extensões do Aniyomi (ex: [itsmechinmoy/dantotsu-updater](https://github.com/itsmechinmoy/dantotsu-updater)).

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
└── .github/workflows/
    ├── build.yml                              # Build em todo push / PR
    └── release.yml                            # Release em tag `v*.*`
```

## Licença

[Apache 2.0](LICENSE)
