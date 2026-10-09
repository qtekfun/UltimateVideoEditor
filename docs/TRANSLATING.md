# Translating ultimateVE

English is the source language. Spanish (`es`) is the first translation. A language is a file: `app/src/main/res/values-xx/strings.xml`
(plus one line in `app/src/main/res/xml/locales_config.xml`). Translations are written by people and bundled in the app; nothing is
downloaded and no translation service or SDK is used (`docs/PRIVACY.md`).

Status: the Projects screen, the New project sheet, About, and the dialogs, bars and notifications of exports, backups and imports
are translated. The editor and the rest are still English and move over in stages (`DECISIONS.md`, "Translations").
`app/src/test/i18n/unmigrated.txt` lists what is still English.

## Adding a language

1. Copy `app/src/main/res/values/strings.xml` to `app/src/main/res/values-xx/strings.xml` (`xx` is the bare language code: `fr`,
   `de`, `pt`; a regional variant shares the translation, so no `values-pt-rBR`). Translate every `<string>` and every `<item>` of a
   `<plurals>`. Leave `translatable="false"` entries (the app name) out.
2. Add `<locale android:name="xx" />` to `app/src/main/res/xml/locales_config.xml`. This one list drives Android 13's system
   per-app language setting and the language picker in About.
3. If the language is not in `pluralForms` of `TranslationsGuardTest`, add its CLDR plural forms there (one, few, many, other...).
4. Run `flock /tmp/gradle-build.lock ./gradlew :app:testDebugUnitTest :app:lintRelease`. `TranslationsGuardTest` fails with the key
   at fault; lint fails the build for `MissingTranslation`, `ExtraTranslation`, `StringFormatInvalid`, `StringFormatMatches` and
   `MissingQuantity`.
5. Look at the screens: Projects, New project, About (long text, landscape, the narrowest phone), an export notification.

No Kotlin changes are needed. The picker lists the language under its own name ("Deutsch"), written by the system, not by a string.

## Rules for the strings

- **Format arguments are positional**: `%1$s`, `%2$d`. A translation may reorder them; it must use the same ones as the source. `%%` is
  a percent sign. Text with a `%` is always formatted by the app, so write `100 %%` and never a lone `%`.
- **Plurals**: every count is a `<plurals>`, with the forms the language needs (Spanish: `one`, `many`, `other`; `many` is
  the form for "1 000 000 de ...", write it even if it looks the same as `other`).
- **Apostrophes and quotes** are escaped in XML resources: `\'` and `\"` (the build fails on a bare apostrophe).
- **No text is built from fragments in code.** A sentence is one string with arguments, so word order can change. Words that are
  data (a project name, a file name, a message that came from the system) are arguments.
- Names of formats and technical terms that people use in English everywhere stay as they are: SDR, HDR, HLG, LUT, Mbps, fps, H.264,
  HEVC, 4K, 1080p, YouTube, TikTok.
- Units in sizes and times (B, KB, MB, GB, s, min, h) are symbols kept in code for now; the words around them are translated.

## Where text comes from

| Where | How |
|---|---|
| Composable code | `stringResource(R.string.x, args)` and `pluralStringResource(R.plurals.x, n, args)` |
| View models, effects, notifications, pure classes with JVM tests | `UiText` (`ui/text/UiText.kt`): a resource id and arguments, resolved where it is shown, so a state kept in a view model follows a language change and the JVM tests need no Android |
| Text of the lower layers (`data/`, `domain/`, `engine/`) | still English strings or exception messages; they reach the screen as `UiText.Raw` and are translated in a later stage |
| Documents shown in About (privacy statement, notices, licence) | English files bundled as assets |

In unit tests, `UiText.english()` (`src/test/.../ui/text/StringsXml.kt`) reads the real `values/strings.xml`, so a test asserts on the
words the user reads, not on a resource id.

## The guard tests

- `TranslationsGuardTest`: every key of `values/` exists in each `values-xx/` and the other way round; the same format arguments; no
  empty text; the plural forms the language needs; every string really formats; `locales_config.xml` lists exactly `en` and the
  translations.
- `HardCodedTextRatchetTest`: no file outside `src/test/i18n/unmigrated.txt` may have words typed into the code (`Text("...")`,
  `contentDescription = "..."`, a message, a notification, a sentence in a string). It fails with `file:line`. The list only shrinks: a
  stage that migrates a screen deletes its entry, and an entry with nothing left to find is an error. A literal that is data and not
  words says why on its line with `// i18n-ok: reason`.
- Android lint (`./gradlew :app:lintRelease`, part of the release CI) fails on the problems above.

## The app language

Android 13 and later: the system's per-app language (Settings > Apps > ultimateVE > Language, or the picker in About, which sets
the same thing through `LocaleManager`). Android 12 and 12L have no such setting: the choice made in About is kept in the app's
preferences and applied to the activity, the application and the export service (`ui/language/AppLocale.kt`). "System default" follows
the phone; a phone language the app does not have shows English.

## Spanish: tone and terminology

Tone: informal "tú". Whatever you tap is an imperative ("Cancela", "Elimina", "Comparte", "Abre el proyecto", "Exporta el archivo de
proyecto"; accessibility descriptions of buttons too), except "Aceptar" for OK; so are instructions to the person ("Elige una carpeta",
"Libera espacio"). Titles, headings and field labels name the thing or the action as an infinitive or a noun ("Renombrar proyecto",
"Exportar película", "Acerca de", "Carpeta de medios", "Buscar proyectos"). Sentence case; typographic quotes «así» around names; `%` with a space before it (`50 %`);
decimal comma comes from the locale, not from the string. Wording neutral between Spain and Latin America ("vídeo", "dispositivo",
"teléfono"); no "vale", "ordenador", "móvil".

| English | Spanish |
|---|---|
| project | proyecto |
| clip | clip |
| timeline | línea de tiempo |
| track / lane | pista |
| frame | fotograma |
| frame rate | velocidad de fotogramas (fps) |
| marker | marcador |
| trim | recortar |
| split | dividir |
| export / import | exportar / importar |
| bundle (`.uvbundle`) | paquete |
| backup | copia de seguridad |
| media (files) | archivos multimedia / medios («Carpeta de medios») |
| footage | material |
| thumbnail | miniatura |
| waveform | forma de onda |
| proxy | proxy |
| cache | caché |
| bitrate | tasa de bits |
| aspect ratio | relación de aspecto |
| resolution | resolución |
| colour space | espacio de color |
| codec | códec |
| canvas | lienzo |
| playhead | cabezal |
| gap | hueco |
| lane (a row of the timeline) | pista |
| beat | pulso |
| captions | subtítulos |
| stickers | pegatinas |
| mixer | mezclador |
| scopes (waveform, vectorscope...) | monitores de vídeo |
| safe zones | zonas seguras |
| crossfade | fundido encadenado |
| fade in / out | fundido de entrada / de salida |
| layout (of panels) | diseño |
| preset | preajuste |
| tray (media tray) | bandeja (de medios) |
| inspector | inspector |
| multicam | multicámara |
| quick edits | ediciones rápidas |
| tracking (motion) | seguimiento |
| detach audio | separar el audio |
| link / linked | vincular / vinculado |
| snap | imán |
| relink | volver a vincular |
| missing | no encontrado / faltan |
| share | compartir |
| duplicate | duplicar |
| rename | renombrar |
| delete (a project) | eliminar |
| clear (a cache) | borrar |
| dismiss | descartar |
| notification | notificación |

Keep one term for one idea across the app; add to this table when a stage introduces a new one.

## Not translated (yet)

- The editor and everything on `src/test/i18n/unmigrated.txt`.
- Technical diagnostics from the lower layers (the verifier's findings, `ProjectError` messages, the export engine's remarks).
- `docs/USER_GUIDE.md`, the website and the in-app toolbar guide (decided in a later stage).
- The privacy statement, the notices and the licence text shown in About.
