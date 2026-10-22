# Filen Cloud Storage Echo Extension

This extension supports Filen Cloud Storage: select a folder in the cloud to view all the audio files stored there

## Getting Started

### 0. Download and install the .eapk
As with all other extensions, download and install the extension: it should appear alongside your other extensions
### 1. Login
Once you've installed the extension, go to Settings and sign in. For now, just enter your email and password; you can ignore the 2FA code (which I'll remove later).
### 2. Set cloud directories
The extension supports specifying one or more cloud folders, with or without recursive searching of subfolders (up to 8 subfolders): simply add the relevant folders with their full paths. 
If entering multiple folders, use a semicolon ; as a separator. 
Support for playlists has also been added: just as with music paths, you can specify either folders containing .m3u/.m3u8 files or multiple individual files. 
Simply use the ; separator to identify them.

### 3. Enjoy
If everything is configured correctly, you should see all the audio files on the Home screen.
By default, the most recently uploaded files are displayed first (that seemed like the most reasonable option to me). 
You can search for files in the Search Feed (using the central search button) and find common albums and artists on the corresponding screens. 
There’s just one limitation: since all files have tags that haven’t been viewed or may not have been curated, the system always searches for the first artist in the case of collaborations, and the album cover is always that of the first track; the same applies to the artist’s image.
This seems like a good compromise to me: after all, I built this extension to have a cloud backup of our favorite songs at my fingertips. 
If you have any suggestions, I recommend contacting me on Discord. I rarely check pull requests. Feel free to fork, modify, or improve it as you see fit.
Enjoy!
### 4. Features request / Improvements

Ping me on Discord (@gheovgos, I don't bite, I promise) for ideas and features!
