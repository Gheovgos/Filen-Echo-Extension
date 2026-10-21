# Filen Cloud Storage Echo Extension

This extension supports Filen Cloud Storage: select a folder in the cloud to view all the audio files stored there

## Getting Started

### 0. Download and install the .eapk
As with all other extensions, download and install the extension: it should appear alongside your other extensions
### 1. Login
Once you've installed the extension, go to Settings and sign in. For now, just enter your email and password; you can ignore the 2FA code (which I'll remove later).
### 2. Set cloud directory
Once you've logged in, enter the full path to the directory. By default, it points to /echomusic. For now, only one folder at a time is supported, and audio files are also searched for in each subfolder. In the future, I'd like to implement the option to skip searching each subfolder and add multiple links to cloud folders.
### 3. Enjoy
If everything is configured correctly, you should see all the audio files on the Home screen. There are still quite a few features to be implemented; I'll create a separate section for future updates.
### 4. TODO for future
All features/classes to be implemented:
- SearchFeedClient
- Library/Playlists
- ArtistClient
- AlbumClient

IDK other features, but I'm open to suggestion
