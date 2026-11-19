# Suivi du développement

Résumé du travail fait le 17/11/2026 (commit `dfdb1e4`, release `v0.1.0`), pour reprendre le
développement sur un autre poste. L'installation d'un nouveau PC est décrite dans
[NOUVEAU_PC.md](NOUVEAU_PC.md) ; aucun secret n'est dans ce dépôt public.

## Ce qui a été fait

### 1. Paroles (inspirées de Namida)

- **Sources, par priorité** (`data/lyrics/LyricsRepositoryImpl.kt`) :
  1. paroles choisies à la main ;
  2. tag du fichier s'il est synchronisé ;
  3. fichier `.lrc` de même nom dans le dossier Drive ;
  4. tag du fichier en texte simple ;
  5. recherche en ligne sur [LRCLIB](https://lrclib.net), désactivable dans le menu des paroles.
- **LRCLIB** (`data/lyrics/LrcLibClient.kt`, `domain/lyrics/LyricsMatcher.kt`) :
  - `GET /api/search` est essayé avec 4 requêtes successives : titre + artiste, puis + album, puis la même chose avec le premier artiste seul ;
  - le score vaut 0,7 × titre + 0,3 × artiste, sur des mots normalisés (sans accents ni mots parasites) avec une tolérance aux fautes, et le seuil est 0,7 ;
  - parmi les résultats de bonne durée (±5 s), les paroles synchronisées passent d'abord ;
  - « pas trouvé » n'est pas redemandé avant 6 h (en mémoire).
- **Parseur LRC** (`domain/lyrics/Lrc.kt`) :
  - plusieurs horodatages par ligne, fractions de 1 à 6 chiffres ;
  - balises `[offset:]` (positif = plus tôt) et `[length:]`, qui sert à étirer les temps des versions accélérées ou ralenties ;
  - LRC enrichi `<mm:ss.xx>` mot à mot ;
  - recherche dichotomique de la ligne courante.
- **Base Room** : la table `lyrics` (`data/db/LyricsDb.kt`) garde le choix manuel, le masquage, le cache LRCLIB et le décalage par piste. Elle est supprimée avec la piste (CASCADE).
- **Écran** (`ui/nowplaying/Lyrics.kt`) :
  - ligne courante à 40 % de la hauteur, voisines estompées, réduites et floutées (Android 12+) ;
  - surlignage karaoké mot à mot ;
  - toucher une ligne = s'y placer ; appui long = copier ;
  - le défilement manuel suspend le suivi, qui reprend 3 s après ;
  - pincer pour zoomer (taille enregistrée) ;
  - décalage par pas de 0,1 s ;
  - recherche manuelle avec aperçu, plein écran, écran gardé allumé.
- Non repris de Namida : la source KuGou, l'éditeur LRC, la traduction et la romanisation, les paroles dans la notification.

### 2. Un dossier Drive = un album

- **Table `albums`** : elle ne stocke plus que `folderId` (unique) et la pochette.
- **Titre, artiste, année, nombre de pistes** : calculés à la lecture par la vue `album_view` (`data/db/Entities.kt`).
  - Titre : le tag Album s'il est commun à tout le dossier (et différent de « Album inconnu »), sinon le nom du dossier.
  - Artiste : l'artiste d'album de plus de la moitié des pistes, sinon « Artistes divers ».
- **Migration Room 1 → 2** (`MIGRATION_1_2` dans `data/db/MusicDatabase.kt`) : un album par dossier, la pochette existante est conservée, les notes et écoutes sont intactes. Elle a été vérifiée sur une vraie base SQLite construite depuis `schemas/1.json`.
- **Scanner** (`drive/scan/DriveMusicScanner.kt`) :
  - un album est créé une fois par dossier contenant de l'audio ;
  - la pochette du dossier (`cover.jpg`…) est prioritaire ;
  - un dossier choisi deux fois (parent et enfant cochés) n'est parcouru qu'une fois.

### 3. Choix des dossiers Drive

- Bouton « Tout cocher / Tout décocher » pour les sous-dossiers affichés.
- **« Dossiers de musique trouvés »** :
  - l'app liste les fichiers audio de Drive et leurs dossiers ;
  - pour chaque branche de « Mon Drive » ou chaque dossier partagé, elle propose l'ancêtre commun des dossiers audio, avec le nombre d'albums et de morceaux ;
  - ces dossiers sont pré-cochés seulement à la première configuration ;
  - le code est dans `ui/MainViewModel.kt` (`suggestMusicFolders`) et `drive/api/DriveApi.kt`.

### 4. Menu latéral et thème

- Le menu s'ouvre avec le bouton ☰ de la Bibliothèque (`ui/menu/AppDrawer.kt`). Il contient :
  - une bannière « Tashu » ;
  - les entrées Bibliothèque, Dossiers Drive et Rescanner ;
  - les réglages du thème.
- Les réglages du thème (`ThemeSettings` dans `domain/model/Models.kt`, enregistrés dans DataStore) :
  - mode Système / Clair / Sombre ;
  - couleur : pochette, fond d'écran (Android 12+) ou couleur fixe ;
  - noir pur ;
  - style de palette MaterialKolor.
- Ces réglages sont fournis à toute l'app par `LocalThemeSettings` (`ui/theme/Theme.kt`, `ui/MainActivity.kt`).

## Ce qui a été vérifié, et ce qui ne l'a pas été

- Vérifié :
  - compilation debug et release ;
  - 28 tests du parseur et du classement LRCLIB, sur de vraies réponses LRCLIB, exécutés sur la JVM hors du dépôt ;
  - migration 1 → 2 sur SQLite ;
  - menu et thème sur émulateur (clair, sombre, couleur fixe, retour qui ferme le menu) ;
  - APK release : démarrage et ouverture de la connexion Google sur émulateur.
- **Pas vérifié** (pas de compte Google sur l'émulateur) :
  - la mise à jour d'une base existante sur le téléphone ;
  - les requêtes Drive du sélecteur ;
  - les paroles sur de vrais morceaux ;
  - les écrans après connexion.
- La détection des dossiers ne voit que les fichiers que Drive classe en `audio/`. Certains `.opus` peuvent manquer à la proposition, mais ils sont quand même indexés.

## Compiler et publier

- JDK **17** obligatoire (Gradle 8.11 / AGP 8.7). Sous Linux, un JRE seul ne suffit pas : il faut `javac`. Pointer Gradle dessus avec `org.gradle.java.home=<chemin du JDK 17>` dans `~/.gradle/gradle.properties`.
- SDK : `platforms;android-35`, `build-tools;35.0.0`, `platform-tools`. Mettre `sdk.dir` dans `local.properties` (non versionné).
- Debug : `./gradlew :app:assembleDebug` (Linux : `bash gradlew …` si le fichier n'est pas exécutable).
- **Release** : `./gradlew :app:assembleRelease` produit un APK **non signé**. Il prend environ 15 min à cause de R8. Le signer ensuite avec la clé de debug du projet :
  ```
  BT=$ANDROID_HOME/build-tools/35.0.0
  $BT/zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
  $BT/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
      --ks-key-alias androiddebugkey --key-pass pass:android --out tashu-player-vX.Y.Z.apk aligned.apk
  ```
  La clé de debug a été choisie volontairement : son SHA-1 est déjà enregistré dans Google Cloud, donc la connexion fonctionne. Pour une vraie publication (Play Store), il faudra créer une clé de release et l'ajouter comme client OAuth Android.
- Publier : `gh release create vX.Y.Z tashu-player-vX.Y.Z.apk --target main` (le dépôt est public).

## Pistes pour la suite

- Tester sur le téléphone : mise à jour depuis la version 1 de la base, sélecteur de dossiers, paroles.
- Afficher le titre d'album calculé (`album_view`) aussi dans le lecteur et la notification : ils montrent encore le tag Album de la piste.
- Paroles : source KuGou, paroles dans la notification, éditeur LRC.
- Clé de release dédiée et `signingConfig` Gradle si l'app doit être publiée.
- Les limites déjà connues sont dans le [README](../README.md#limites-connues).
