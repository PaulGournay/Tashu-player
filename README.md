# Tashu-player : lecteur audiophile Android, source unique Google Drive

Kotlin 100 %, Jetpack Compose (Material You piloté par la pochette), Media3/ExoPlayer
(`MediaLibraryService`), Room, Hilt, Credential Manager + Google Drive REST API v3 (`drive.readonly`).

## Architecture

Module unique `:app`, découpé en couches Clean Architecture. Les dépendances pointent vers `domain`.

```
app/src/main/java/com/tashu/player/
├── TashuApp.kt                       @HiltAndroidApp + WorkManager (scan périodique)
├── di/AppModule.kt                   OkHttp, Json, Room, bindings repositories
├── domain/                           Kotlin pur, sans Android
│   ├── model/Models.kt               Track, Album, AudioCodec, SmartPlaylistDefinition, AudioSettings…
│   ├── lyrics/                       parseur LRC (LRC enrichi, [offset:], [length:]) + choix du résultat LRCLIB
│   └── repository/                   LibraryRepository, SettingsRepository, LyricsRepository (interfaces)
├── data/
│   ├── db/Entities.kt                tracks, albums (1 dossier Drive = 1 album), artists, genres, composers, folders, smart_playlists, queues
│   ├── db/LyricsDb.kt                lyrics : choix manuel, cache LRCLIB, décalage par piste
│   ├── db/Daos.kt                    DAO Flow + @RawQuery pour les smart playlists
│   ├── db/SmartPlaylistQueryBuilder  règles vers SQL paramétré (colonnes en liste blanche)
│   ├── lyrics/                       client LRCLIB + LyricsRepositoryImpl (priorité des sources)
│   └── repo/                         LibraryRepositoryImpl, SettingsRepositoryImpl (DataStore)
├── drive/
│   ├── auth/GoogleAuthManager.kt     Credential Manager (identité) + AuthorizationClient (token OAuth)
│   ├── api/DriveApi.kt               files.list paginé, Range Requests, Bearer, rejeu sur 401
│   └── scan/
│       ├── DriveRangeReader.kt       accès aléatoire par blocs de 64 Kio (+ MediaDataSource)
│       ├── TagParser.kt              FLAC STREAMINFO/Vorbis comments, Ogg/Opus, ID3v2 (ReplayGain, paroles)
│       ├── DriveMusicScanner.kt      parcours récursif incrémental vers Room
│       └── ScanWorker.kt             WorkManager (à la demande + toutes les 12 h en Wi-Fi)
├── playback/
│   ├── datasource/GoogleDriveDataSource.kt   gdrive://<id> vers alt=media + Bearer + Range
│   ├── cache/PlaybackModule.kt       cache hors-ligne (sans éviction) > cache LRU 1 Gio > Drive
│   ├── cache/OfflineManager.kt       téléchargements, prefetch piste suivante, quota disque
│   ├── dsp/ParametricEqAudioProcessor.kt     PEQ 10 bandes (biquads RBJ) dans le DefaultAudioSink
│   ├── dsp/AudioEffectsController.kt GEQ 10 bandes + ReplayGain + limiteur (DynamicsProcessing)
│   ├── MusicPlaybackService.kt       MediaLibraryService : gapless, Auto, notification, extras audio
│   ├── QueueManager.kt               files d'attente multiples avec position exacte
│   ├── CrossfadeController.kt        fondu intelligent (désactivé entre pistes d'un même album)
│   ├── UsbDacRouter.kt               routage vers DAC USB (setPreferredAudioDevice)
│   ├── MediaItemMapper.kt            Track vers MediaItem (clé de cache = fileId)
│   └── PlayerConnection.kt           MediaController côté UI, état en StateFlow
└── ui/
    ├── MainActivity.kt               edge-to-edge, consentement OAuth, notifications
    ├── MainViewModel.kt              bibliothèque, onboarding, sélecteur de dossiers
    ├── navigation/TashuNavHost.kt
    ├── theme/Theme.kt                Palette API vers schéma Material 3 complet (MaterialKolor), selon les réglages du thème
    ├── menu/AppDrawer.kt             menu latéral : bannière Tashu, raccourcis, thème (mode, couleur, noir pur, style)
    ├── onboarding/                   connexion, choix des dossiers Drive
    ├── library/                      albums, morceaux, Mode Fichiers, listes dynamiques, mini-lecteur
    └── nowplaying/                   NowPlayingScreen (3 styles), paroles synchronisées, recherche, plein écran
```

## Paroles

Inspirées de [Namida](https://github.com/namidaco/namida). Ordre des sources : choix manuel, tag
synchronisé du fichier, `.lrc` de même nom dans le dossier Drive, tag en texte simple, puis
[LRCLIB](https://lrclib.net) (titre, artiste et album envoyés ; désactivable dans le menu des paroles).
Le résultat LRCLIB est choisi par ressemblance du titre et de l'artiste, puis par durée la plus proche,
et mis en cache dans Room. Affichage : ligne courante à 40 % de la hauteur, voisines estompées,
mots surlignés en LRC enrichi, toucher une ligne pour s'y placer, appui long pour la copier,
pincer pour zoomer, décalage réglable par pas de 0,1 s, plein écran.

## Albums

Chaque dossier Drive qui contient des fichiers audio est un album. Son titre est le tag Album
s'il est commun à toutes les pistes du dossier, sinon le nom du dossier ; son artiste est l'artiste
d'album de plus de la moitié des pistes, sinon « Artistes divers ».

## Configuration Google Cloud Console

1. Sur https://console.cloud.google.com, créez un projet (par exemple `tashu-player`).
2. Dans **APIs & Services, Library**, activez **Google Drive API**.
3. Dans **Google Auth Platform** (anciennement *OAuth consent screen*) :
   - type d'audience **External** ;
   - nom de l'app, e-mail de support ;
   - **Data access, Add scopes** : `https://www.googleapis.com/auth/drive.readonly` ;
   - **Audience, Test users** : ajoutez votre compte Google. Tant que l'app est en mode *Testing*,
     seuls ces comptes peuvent se connecter.
4. Dans **Clients, Create client**, créez **deux** clients OAuth :
   - **Android** : package `com.tashu.player` et empreinte SHA-1 du keystore.
     Pour le debug : `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android`.
     Créez un client Android de plus pour la clé de release ou celle de Play App Signing.
   - **Web application** : aucun redirect nécessaire. C'est son **Client ID** que l'app utilise
     comme `serverClientId` de Credential Manager.
5. Ajoutez le Client ID Web dans `~/.gradle/gradle.properties` (hors dépôt) :
   ```
   TASHU_WEB_CLIENT_ID=1234567890-abc.apps.googleusercontent.com
   ```
6. `drive.readonly` est un scope **restreint**. Pour une publication publique, Google exige une
   vérification de l'app et un audit de sécurité (CASA). Pour un usage personnel ou avec moins de
   100 testeurs, le mode *Testing* suffit, mais les grants y expirent au bout de 7 jours.

## Ouvrir et compiler

- Android Studio (Ladybug ou plus récent), JDK 17, SDK 35.
- Le wrapper Gradle 8.11.1 est versionné : `.\gradlew.bat :app:assembleDebug` (Windows) ou `./gradlew :app:assembleDebug`.
- Installation sur téléphone : `.\gradlew.bat :app:installDebug` (USB ou débogage sans fil).
- Montez les versions de `gradle/libs.versions.toml` via *Project Structure, Suggestions* si besoin.
- Nouveau poste de développement : voir [docs/NOUVEAU_PC.md](docs/NOUVEAU_PC.md).
- Ce qui a été fait, ce qui reste à vérifier et la suite : voir [docs/SUIVI_DEV.md](docs/SUIVI_DEV.md).

## Limites connues

- **ALAC** : le décodeur MediaCodec n'est pas présent sur tous les appareils. Ajoutez l'extension
  FFmpeg de Media3 (`decoder_ffmpeg`, à compiler soi-même) ; `EXTENSION_RENDERER_MODE_PREFER` est déjà activé.
- **ReplayGain** est appliqué à la transition de piste, via le gain d'entrée DynamicsProcessing.
  Le tampon audio déjà décodé (environ 100 à 500 ms) passe donc encore au gain précédent.
- **Crossfade** : il fait un fondu sortant puis un fondu entrant, sans superposition des deux pistes.
  Une vraie superposition demande deux ExoPlayer mixés.
- **Hi-Res bout en bout** : la sortie float (24 bits et plus) et le mode bit-perfect USB
  (API 34+, `AudioMixerAttributes`) ne sont pas encore activés. Le DAC USB reçoit aujourd'hui un flux PCM 16 bits.
- **Chromecast** : `media3-cast` est déclaré mais pas encore branché. Le Chromecast ne peut pas
  envoyer l'en-tête `Authorization` vers Drive, et Drive refuse le token passé en paramètre d'URL.
  Il faut donc un petit proxy HTTP local sur le téléphone (par exemple NanoHTTPD) qui relaie le flux authentifié.
- **Android Auto** : la navigation passe par Albums et Morceaux. Les vignettes des listes ont besoin d'URI
  `content://` (FileProvider) : les `file://` actuels ne s'affichent que pour la piste en cours.
