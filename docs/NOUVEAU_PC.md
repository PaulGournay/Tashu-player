# Développer Tashu-player sur un nouveau PC

Ce guide ne contient aucun secret. Le Client ID OAuth et le keystore de debug sont conservés
hors du dépôt (Drive personnel du mainteneur).

## 1. Outils (Windows)

```powershell
winget install --id Git.Git -e
winget install --id GitHub.cli -e
winget install --id Microsoft.OpenJDK.17 -e
winget install --id Google.AndroidStudio -e
```

Au premier lancement, l'assistant d'Android Studio installe le SDK. Ajoutez ensuite
**Android SDK Platform 35** via *Settings, Languages & Frameworks, Android SDK*.

## 2. Récupérer le code

```powershell
gh auth login
gh repo clone PaulGournay/Tashu-player
cd Tashu-player
```

## 3. Keystore de debug

Le client OAuth Android est lié à l'empreinte SHA-1 d'un keystore précis. Pour que la connexion
Google fonctionne, il faut soit :

- **copier le keystore de debug existant** vers `%USERPROFILE%\.android\debug.keystore`
  (remplace celui généré par Android Studio) ;
- soit **ajouter un nouveau client Android** dans Google Cloud Console (projet `tashu-player`)
  avec le SHA-1 du nouveau PC :
  ```powershell
  keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -storepass android
  ```

## 4. Client ID Web

Créez `%USERPROFILE%\.gradle\gradle.properties` :

```
TASHU_WEB_CLIENT_ID=<client-id-web>.apps.googleusercontent.com
```

## 5. Compiler et installer

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:installDebug
```

`installDebug` exige un téléphone connecté en USB ou en débogage sans fil :

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb pair <ip>:<port-association> <code>
& $adb connect <ip>:<port>
```
