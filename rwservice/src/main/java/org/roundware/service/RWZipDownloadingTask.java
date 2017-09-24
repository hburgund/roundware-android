/**
 * Roundware Android code is released under the terms of the GNU General Public License.
 * See COPYRIGHT.txt, AUTHORS.txt, and LICENSE.txt in the project root directory for details.
 */
package org.roundware.service;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import android.os.AsyncTask;
import android.util.Log;


/**
 * Asynchronous task that handles the downloading and unpacking of a zip
 * file.
 * 
 * @author Rob Knapen
 */
public class RWZipDownloadingTask extends AsyncTask<Void, Void, String> {

    // debugging
    private final static String TAG = "RWZipDownloadingTask";
    private final static boolean D = true;

    private static final int DOWNLOADING_EVENT_INTERVAL_MSEC = 2000; // 2.0 sec between updates

    // fields
    private String mFileUrl = null;
    private String mTargetDirName = null;
    private long mLastDownloadingEventMsec = 0;
    private StateListener mListener;

    /**
     * Listener interface for callbacks during downloading and unpacking
     * 
     * @author Rob Knapen
     */
    public interface StateListener {
        public void downloadingStarted(long timeStampMsec);
        public void downloading(long timeStampMsec, long bytesProcessed, long totalBytes);
        public void downloadingFinished(long timeStampMsec, String filesStorageDir);
        public void downloadingFailed(long timeStampMsec, String errorMessage);
    }

    
    /**
     * Creates an instance of the downloading task with the specified
     * parameters. Make sure to specify a valid URL for a zip file to be
     * downloaded and a valid directory name for extracting the files to.
     * When the directory does not exist and attempt will be made to create
     * it.
     * 
     * @param fileUrl URL of zip file to be downloaded
     * @param targetDirName Name of directory to extract files to
     * @param listener to use for callbacks
     */
    public RWZipDownloadingTask(String fileUrl, String targetDirName, StateListener listener) {
        mListener = listener;
        mFileUrl = fileUrl;

        if ((targetDirName == null) || (!targetDirName.endsWith(File.separator))) {
            mTargetDirName = targetDirName + File.separator;
        } else {
            mTargetDirName = targetDirName;
        }

        // ensure target folder exists
        File targetDir = new File(mTargetDirName);
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
    }
    

    @Override
    protected String doInBackground(Void... params) {
        if (D) { Log.d(TAG, "Starting download of: " + mFileUrl, null); }

        // download file from server
        HttpURLConnection connection = null;
        try {
            URL url = new URL(mFileUrl);
            connection = (HttpURLConnection) url.openConnection();
            int responseCode = connection.getResponseCode();

            // follow permanent or temporary redirects to new location
            if ((responseCode == HttpURLConnection.HTTP_MOVED_PERM) || (responseCode == HttpURLConnection.HTTP_MOVED_TEMP)) {
                String newUrl = connection.getHeaderField("Location");
                connection = (HttpURLConnection) (new URL(newUrl).openConnection());
                if (D) { Log.d(TAG, "Redirected to: " + newUrl); }
                responseCode = connection.getResponseCode();
            }

            if (responseCode != 200) {
                throw new java.net.ConnectException("HTTP code " + responseCode);
            }
        } catch (IOException e) {
            String msg = "Download failed: " + e.getMessage();
            Log.e(TAG, msg, null);
            return msg;
        }

        if (mListener != null) {
            long currentMillis = System.currentTimeMillis();
            mListener.downloadingStarted(currentMillis);
        }

        try {
            int contentLength = connection.getContentLength();
            long bytesProcessed = 0;

            ZipInputStream zipInputStream = new ZipInputStream(connection.getInputStream());

            for (ZipEntry zipEntry = zipInputStream.getNextEntry(); zipEntry != null; zipEntry = zipInputStream.getNextEntry()) {
                if (D) { Log.d(TAG, "Extracting entry: " + zipEntry.getName() + " ..."); }

                String innerFileName = mTargetDirName + zipEntry.getName();
                File innerFile = new File(innerFileName);
                if (innerFile.isHidden()) {
                    if (D) { Log.d(TAG, "Skipping hidden file: " + innerFile.getName()); }
                    continue;
                }

                if (innerFileName.endsWith(File.separator)) {
                    if (!innerFile.exists()) {
                        if (D) { Log.d(TAG, "Creating folder(s): " + innerFileName + " ..."); }
                        if (!innerFile.mkdirs()) {
                            Log.e(TAG, "Could not create directory: " + innerFileName);
                        }
                    }
                } else {
                    if (D) { Log.d(TAG, "Unpacking file: " + innerFileName + " ..."); }
                    FileOutputStream outputStream = new FileOutputStream(innerFile);
                    final int BUFFER_SIZE = 2048;
                    BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(outputStream, BUFFER_SIZE);
                    int count;
                    byte[] buffer = new byte[BUFFER_SIZE];
                    while ((count = zipInputStream.read(buffer, 0, BUFFER_SIZE)) != -1) {
                        bufferedOutputStream.write(buffer, 0, count);
                        bytesProcessed += count;
                    }

                    bufferedOutputStream.flush();
                    bufferedOutputStream.close();
                }

                if (D) { Log.d(TAG, "Processed " + bytesProcessed + " bytes"); }

                if (mListener != null) {
                    long currentMillis = System.currentTimeMillis();
                    if ((currentMillis - mLastDownloadingEventMsec) > DOWNLOADING_EVENT_INTERVAL_MSEC) {
                        mLastDownloadingEventMsec = currentMillis;
                        mListener.downloading(currentMillis, bytesProcessed, contentLength);
                    }
                }

                zipInputStream.closeEntry();
            }
            zipInputStream.close();
            if (D) { Log.d(TAG, "Download complete", null); }
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "Error while downloading and unpacking: " + e.getMessage());
            return "Download of app content files failed! Please try again later.";
        }

        return null;
    }


    /**
     * Post execute - currently nothing to be done here.
     */
    @Override
    protected void onPostExecute(String result) {
        super.onPostExecute(result);
        if (mListener != null) {
            if ((result == null) || (result.length() == 0)) {
                mListener.downloadingFinished(System.currentTimeMillis(), mTargetDirName);
            } else {
                mListener.downloadingFailed(System.currentTimeMillis(), result);
            }
        }
    }

}
