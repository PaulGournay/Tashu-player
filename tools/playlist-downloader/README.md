# Téléchargeur de playlists (yt-dlp)

Petite interface Windows autour de yt-dlp : un lien de playlist YouTube Music en entrée, des MP3 numérotés en sortie,
avec titre, artiste, numéro de piste et pochette carrée intégrés. Les fichiers obtenus peuvent ensuite être déposés
dans le dossier Drive indexé par Tashu.

## Installation

```powershell
python -m pip install -U yt-dlp
winget install Gyan.FFmpeg
```

## Utilisation

1. Double-cliquer sur `lancer.bat`.
2. Coller le lien de la playlist, choisir le dossier de destination.
3. Cliquer sur « Télécharger ». La barre du haut suit la playlist, celle du bas le morceau en cours.

Options de yt-dlp utilisées (identiques à la commande d'origine) : meilleure qualité audio, conversion MP3 V0,
métadonnées et pochette intégrées, pochette recadrée en carré, numéro de piste = position dans la playlist,
nom de fichier `NN - Titre.mp3`. Les `.webp` restants sont supprimés à la fin.

Ajouts : option « sous-dossier au nom de la playlist », `--ignore-errors` (une vidéo indisponible ne bloque pas
le reste), bouton Arrêter, dernier dossier mémorisé.

N'utilisez cet outil que pour du contenu que vous avez le droit de télécharger.
