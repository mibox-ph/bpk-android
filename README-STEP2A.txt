BPK STAFF APP, STEP 2A (simpler way, no folders needed)
=======================================================
All files in this folder go to GitHub as plain files. GitHub arranges them into folders by itself.

1. Make a NEW private repository on GitHub named bpk-android (delete the old one first:
   Settings > scroll to the bottom > Delete this repository).
2. On the new repository page click "uploading an existing file".
3. Open this folder, select ALL the files EXCEPT WORKFLOW-COPY-ME.txt and README-STEP2A.txt,
   and drag them into the GitHub upload page. There are 8 files. Do not rename them. Click "Commit changes".
4. Now create the build instructions file:
   - In the repository click  Add file > Create new file.
   - In the name box type exactly:   .github/workflows/build-apk.yml
     (when you type each / the box makes a folder).
   - Open WORKFLOW-COPY-ME.txt in Notepad, select all, copy, and paste it into the big editor box.
   - Click "Commit changes" > "Commit changes".
5. Click the "Actions" tab. Click "Build APK" on the left, then the "Run workflow" button > "Run workflow".
   Wait about 5 minutes. A green check means success. Then continue with Part B step 7 and Parts C and D
   of the earlier instructions (download "BPK-Staff-APK", install the .apk, run the test).
If the run has a red X, click it, click "build", and send me the red error lines.
