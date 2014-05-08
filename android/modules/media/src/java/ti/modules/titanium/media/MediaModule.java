/**
 * Appcelerator Titanium Mobile
 * Copyright (c) 2009-2013 by Appcelerator, Inc. All Rights Reserved.
 * Licensed under the terms of the Apache Public License
 * Please see the LICENSE included with this distribution for details.
 */
package ti.modules.titanium.media;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import org.appcelerator.kroll.KrollDict;
import org.appcelerator.kroll.KrollFunction;
import org.appcelerator.kroll.KrollModule;
import org.appcelerator.kroll.KrollObject;
import org.appcelerator.kroll.KrollRuntime;
import org.appcelerator.kroll.annotations.Kroll;
import org.appcelerator.kroll.common.Log;
import org.appcelerator.titanium.ContextSpecific;
import org.appcelerator.titanium.TiApplication;
import org.appcelerator.titanium.TiBaseActivity;
import org.appcelerator.titanium.TiBlob;
import org.appcelerator.titanium.TiContext;
import org.appcelerator.titanium.io.TiBaseFile;
import org.appcelerator.titanium.io.TiFileFactory;
import org.appcelerator.titanium.proxy.TiViewProxy;
import org.appcelerator.titanium.util.TiActivityResultHandler;
import org.appcelerator.titanium.util.TiActivitySupport;
import org.appcelerator.titanium.util.TiConvert;
import org.appcelerator.titanium.util.TiFileHelper;
import org.appcelerator.titanium.util.TiIntentWrapper;
import org.appcelerator.titanium.util.TiUIHelper;

import ti.modules.titanium.media.android.AndroidModule.MediaScannerClient;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.MediaColumns;
import android.view.Window;

@Kroll.module @ContextSpecific
public class MediaModule extends KrollModule
	implements Handler.Callback
{
	private static final String TAG = "TiMedia";

	private static final long[] DEFAULT_VIBRATE_PATTERN = { 100L, 250L };
	private static final String PHOTO_DCIM_CAMERA = "/sdcard/dcim/Camera";

	protected static final int MSG_INVOKE_CALLBACK = KrollModule.MSG_LAST_ID + 100;
	protected static final int MSG_LAST_ID = MSG_INVOKE_CALLBACK;

	// The mode FOCUS_MODE_CONTINUOUS_PICTURE is added in API 14
	public static final String FOCUS_MODE_CONTINUOUS_PICTURE = "continuous-picture";

	@Kroll.constant public static final int UNKNOWN_ERROR = -1;
	@Kroll.constant public static final int NO_ERROR = 0;
	@Kroll.constant public static final int DEVICE_BUSY = 1;
	@Kroll.constant public static final int NO_CAMERA = 2;
	@Kroll.constant public static final int NO_VIDEO = 3;

	@Kroll.constant public static final int VIDEO_SCALING_NONE = 0;
	@Kroll.constant public static final int VIDEO_SCALING_ASPECT_FILL = 1;
	@Kroll.constant public static final int VIDEO_SCALING_ASPECT_FIT = 2;
	@Kroll.constant public static final int VIDEO_SCALING_MODE_FILL = 3;

	@Kroll.constant public static final int VIDEO_CONTROL_DEFAULT = 0;
	@Kroll.constant public static final int VIDEO_CONTROL_EMBEDDED = 1;
	@Kroll.constant public static final int VIDEO_CONTROL_FULLSCREEN = 2;
	@Kroll.constant public static final int VIDEO_CONTROL_NONE = 3;
	@Kroll.constant public static final int VIDEO_CONTROL_HIDDEN = 4;

	@Kroll.constant public static final int VIDEO_LOAD_STATE_UNKNOWN = 0;
	@Kroll.constant public static final int VIDEO_LOAD_STATE_PLAYABLE = 1 << 0;
	@Kroll.constant public static final int VIDEO_LOAD_STATE_PLAYTHROUGH_OK = 1 << 1;
	@Kroll.constant public static final int VIDEO_LOAD_STATE_STALLED = 1 << 2;

	@Kroll.constant public static final int VIDEO_PLAYBACK_STATE_STOPPED = 0;
	@Kroll.constant public static final int VIDEO_PLAYBACK_STATE_PLAYING = 1;
	@Kroll.constant public static final int VIDEO_PLAYBACK_STATE_PAUSED = 2;
	@Kroll.constant public static final int VIDEO_PLAYBACK_STATE_INTERRUPTED = 3;
	@Kroll.constant public static final int VIDEO_PLAYBACK_STATE_SEEKING_FORWARD = 4;
	@Kroll.constant public static final int VIDEO_PLAYBACK_STATE_SEEKING_BACKWARD = 5;

	@Kroll.constant public static final int VIDEO_FINISH_REASON_PLAYBACK_ENDED = 0;
	@Kroll.constant public static final int VIDEO_FINISH_REASON_PLAYBACK_ERROR = 1;
	@Kroll.constant public static final int VIDEO_FINISH_REASON_USER_EXITED = 2;

	@Kroll.constant public static final String MEDIA_TYPE_PHOTO = "public.image";
	@Kroll.constant public static final String MEDIA_TYPE_VIDEO = "public.video";

	@Kroll.constant public static final int CAMERA_FRONT = 0;
	@Kroll.constant public static final int CAMERA_REAR = 1;

	public MediaModule()
	{
		super();
	}

	public MediaModule(TiContext tiContext)
	{
		this();
	}

	@Kroll.method
	public void vibrate(@Kroll.argument(optional=true) long[] pattern)
	{
		if ((pattern == null) || (pattern.length == 0)) {
			pattern = DEFAULT_VIBRATE_PATTERN;
		}
		Vibrator vibrator = (Vibrator) TiApplication.getInstance().getSystemService(Context.VIBRATOR_SERVICE);
		if (vibrator != null) {
			vibrator.vibrate(pattern, -1);
		}
	}

	@Kroll.method
	public void showCamera(@SuppressWarnings("rawtypes") HashMap options)
	{
		Activity activity = TiApplication.getInstance().getCurrentActivity();

		Log.d(TAG, "showCamera called", Log.DEBUG_MODE);

		KrollFunction successCallback = null;
		KrollFunction cancelCallback = null;
		KrollFunction errorCallback = null;
		boolean autohide = true;
		boolean saveToPhotoGallery = false;
		long resolution = 0;

		if (options.containsKey("success")) {
			successCallback = (KrollFunction) options.get("success");
		}
		if (options.containsKey("cancel")) {
			cancelCallback = (KrollFunction) options.get("cancel");
		}
		if (options.containsKey("error")) {
			errorCallback = (KrollFunction) options.get("error");
		}

		Object autohideOption = options.get("autohide");
		if (autohideOption != null) {
			autohide = TiConvert.toBoolean(autohideOption);
		}

		// Using long may be overkill here but better safe than sorry.
		if (options.containsKey("resolution")) {
			resolution = Long.parseLong(TiConvert.toString(options.get("resolution")));
		}

		Object saveToPhotoGalleryOption = options.get("saveToPhotoGallery");
		if (saveToPhotoGalleryOption != null) {
			saveToPhotoGallery = TiConvert.toBoolean(saveToPhotoGalleryOption);
		}

		// Use our own custom camera activity when an overlay is provided.
		if (options.containsKey("overlay")) {
			TiCameraActivity.overlayProxy = (TiViewProxy) options
					.get("overlay");

			TiCameraActivity.callbackContext = getKrollObject();
			TiCameraActivity.successCallback = successCallback;
			TiCameraActivity.errorCallback = errorCallback;
			TiCameraActivity.cancelCallback = cancelCallback;
			TiCameraActivity.saveToPhotoGallery = saveToPhotoGallery;
			TiCameraActivity.whichCamera = CAMERA_REAR; // default.

			// This option is only applicable when running the custom
			// TiCameraActivity, since we can't direct the built-in
			// Activity to open a specific camera.
			Object whichCamera = options.get("whichCamera");
			if (whichCamera != null) {
				TiCameraActivity.whichCamera = TiConvert.toInt(whichCamera);
			}
			TiCameraActivity.autohide = autohide;
			TiCameraActivity.resolution = resolution;

			Intent intent = new Intent(activity, TiCameraActivity.class);
			activity.startActivity(intent);
			return;
		}

		Camera camera = null;
		try {
			camera = Camera.open();
			if (camera != null) {
				camera.release();
				camera = null;
			}

		} catch (Throwable t) {
			if (camera != null) {
				camera.release();
			}

			if (errorCallback != null) {
				errorCallback.call(
						getKrollObject(),
						new Object[] { createErrorResponse(NO_CAMERA,
								"Camera not available.") });
			}

			return;
		}

		TiActivitySupport activitySupport = (TiActivitySupport) activity;
		TiFileHelper tfh = TiFileHelper.getInstance();

		TiIntentWrapper cameraIntent = new TiIntentWrapper(new Intent());
		if (TiCameraActivity.overlayProxy == null) {
			cameraIntent.getIntent().setAction(
					MediaStore.ACTION_IMAGE_CAPTURE);
			cameraIntent.getIntent().addCategory(Intent.CATEGORY_DEFAULT);
		} else {
			cameraIntent.getIntent().setClass(
					TiApplication.getInstance().getBaseContext(),
					TiCameraActivity.class);
		}

		cameraIntent.setWindowId(TiIntentWrapper.createActivityName("CAMERA"));
		PackageManager pm = (PackageManager) activity.getPackageManager();
		List<ResolveInfo> activities = pm.queryIntentActivities(
				cameraIntent.getIntent(), PackageManager.MATCH_DEFAULT_ONLY);

		// See if it's the HTC camera app
		boolean isHTCCameraApp = false;

		for (ResolveInfo rs : activities) {
			try {
				if (rs.activityInfo.applicationInfo.sourceDir.contains("HTC")
						|| Build.MANUFACTURER.equals("HTC")) {
					isHTCCameraApp = true;
					break;
				}
			} catch (NullPointerException e) {
				// Ignore
			}
		}

		File imageDir = null;
		File imageFile = null;

		try {
			if (saveToPhotoGallery) {
				// HTC camera application will create its own gallery image
				// file.
				if (!isHTCCameraApp) {
					imageFile = createGalleryImageFile();
				}

			} else {
				if (activity.getIntent() != null) {
					String name = TiApplication.getInstance().getAppInfo()
							.getName();
					// For HTC cameras, specifying the directory from
					// getExternalStorageDirectory is /mnt/sdcard and
					// using that path prevents the gallery from recognizing it.
					// To avoid this we use /sdcard instead
					// (this is a legacy path we've been using)
					if (isHTCCameraApp) {
						imageDir = new File(PHOTO_DCIM_CAMERA, name);
					} else {
						File rootsd = Environment.getExternalStorageDirectory();
						imageDir = new File(rootsd.getAbsolutePath()
								+ "/dcim/Camera/", name);
					}
					if (!imageDir.exists()) {
						imageDir.mkdirs();
						if (!imageDir.exists()) {
							Log.w(TAG,
									"Attempt to create '"
											+ imageDir.getAbsolutePath()
											+ "' failed silently.");
						}
					}

				} else {
					imageDir = tfh.getDataDirectory(false);
				}

				imageFile = tfh.getTempFile(imageDir, ".jpg", true);
			}

		} catch (IOException e) {
			Log.e(TAG, "Unable to create temp file", e);
			if (errorCallback != null) {
				errorCallback.callAsync(getKrollObject(),
						createErrorResponse(UNKNOWN_ERROR, e.getMessage()));
			}

			return;
		}

		// Get the taken date for the last image in EXTERNAL_CONTENT_URI.
		String[] projection = {
			Images.ImageColumns.DATE_TAKEN
		};
		String dateTaken = null;
		Cursor c = activity.getContentResolver().query(Images.Media.EXTERNAL_CONTENT_URI, projection, null, null,
				Images.ImageColumns.DATE_TAKEN);
		if (c != null) {
			if (c.moveToLast()) {
				dateTaken = c.getString(0);
			}
			c.close();
			c = null;
		}

		CameraResultHandler resultHandler = new CameraResultHandler();
		resultHandler.imageFile = imageFile;
		resultHandler.saveToPhotoGallery = saveToPhotoGallery;
		resultHandler.successCallback = successCallback;
		resultHandler.cancelCallback = cancelCallback;
		resultHandler.errorCallback = errorCallback;
		resultHandler.activitySupport = activitySupport;
		resultHandler.cameraIntent = cameraIntent.getIntent();
		resultHandler.dateTaken_lastImageInExternalContentURI = dateTaken;

		if (imageFile != null) {
			String imageUrl = "file://" + imageFile.getAbsolutePath();
			cameraIntent.getIntent().putExtra(MediaStore.EXTRA_OUTPUT,
					Uri.parse(imageUrl));
			resultHandler.imageUrl = imageUrl;
		}

		activity.runOnUiThread(resultHandler);
	}

	@Kroll.method
	public void hideCamera()
	{
		// make sure the preview / camera are open before trying to hide
		if (TiCameraActivity.cameraActivity != null) {
			TiCameraActivity.hide();
		} else {
			Log.e(TAG, "Camera preview is not open, unable to hide");
		}

	}

	/**
	 * Object that is used to wrap required fields for async processing when invoking 
	 * success, error , etc callbacks for camera
	 */
	private class CallbackWrapper
	{
		public TiBaseActivity callbackActivity;
		public KrollFunction callback;
		public KrollObject krollObject;
		public KrollDict callbackArgs;

		CallbackWrapper(TiBaseActivity callbackActivity, KrollFunction callback, KrollObject krollObject, KrollDict callbackArgs)
		{
			this.callbackActivity = callbackActivity;
			this.callback = callback;
			this.krollObject = krollObject;
			this.callbackArgs = callbackArgs;
		}
	}

	/**
	 * @see org.appcelerator.kroll.KrollProxy#handleMessage(android.os.Message)
	 */
	@Override
	public boolean handleMessage(Message message)
	{
		switch (message.what) {
			case MSG_INVOKE_CALLBACK: {
				CallbackWrapper callbackWrapper = (CallbackWrapper) message.obj;
				doInvokeCallback(callbackWrapper.callbackActivity, callbackWrapper.callback, callbackWrapper.krollObject, callbackWrapper.callbackArgs);

				return true;
			}
		}

		return super.handleMessage(message);
	}

	public static File createGalleryImageFile() {
		File pictureDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
		File appPictureDir = new File(pictureDir, TiApplication.getInstance().getAppInfo().getName());
		if (!appPictureDir.exists()) {
			if (!appPictureDir.mkdirs()) {
				Log.e(TAG, "Failed to create application gallery directory.");
				return null;
			}
		}

		File imageFile;
		try {
			imageFile = TiFileHelper.getInstance().getTempFile(appPictureDir, ".jpg", false);

		} catch (IOException e) {
			Log.e(TAG, "Failed to create gallery image file: " + e.getMessage());
			return null;
		}

		return imageFile;
	}

	private void invokeCallback(TiBaseActivity callbackActivity, KrollFunction callback, KrollObject krollObject, KrollDict callbackArgs)
	{
		if (KrollRuntime.getInstance().isRuntimeThread()) {
			doInvokeCallback(callbackActivity, callback, krollObject, callbackArgs);

		} else {
			CallbackWrapper callbackWrapper = new CallbackWrapper(callbackActivity, callback, krollObject, callbackArgs);
			Message message = getRuntimeHandler().obtainMessage(MSG_INVOKE_CALLBACK, callbackWrapper);
			message.sendToTarget();
		}
	}

	private void doInvokeCallback(TiBaseActivity callbackActivity, KrollFunction callback, KrollObject krollObject, KrollDict callbackArgs)
	{
		if (callbackActivity.isResumed) {
			callback.callAsync(krollObject, callbackArgs);

		} else {
			CallbackWrapper callbackWrapper = new CallbackWrapper(callbackActivity, callback, krollObject, callbackArgs);
			Message message = getRuntimeHandler().obtainMessage(MSG_INVOKE_CALLBACK, callbackWrapper);
			message.sendToTarget();
		}
	}

	protected class CameraResultHandler implements TiActivityResultHandler, Runnable
	{
		protected File imageFile;
		protected String imageUrl;
		protected boolean saveToPhotoGallery;
		protected int code;
		protected KrollFunction successCallback, cancelCallback, errorCallback;
		protected TiActivitySupport activitySupport;
		protected Intent cameraIntent;
		protected String dateTaken_lastImageInExternalContentURI;

		@Override
		public void run()
		{
			code = activitySupport.getUniqueResultCode();
			activitySupport.launchActivityForResult(cameraIntent, code, this);
		}

		@Override
		public void onResult(Activity activity, int requestCode, int resultCode, Intent data)
		{
			if (resultCode == Activity.RESULT_CANCELED) {
				if (imageFile != null) {
					imageFile.delete();
				}
				if (cancelCallback != null) {
					KrollDict response = new KrollDict();
					response.putCodeAndMessage(NO_ERROR, null);
					cancelCallback.callAsync(getKrollObject(), response);
				}

			} else {
				// If data is null, the image is not automatically saved to the gallery, so we need to process it with
				// the one we created
				if (data == null) {
					processImage(activity);

				} else {
					// Get the content information about the saved image
					String[] projection = {
						Images.Media.TITLE,
						Images.Media.DISPLAY_NAME,
						Images.Media.MIME_TYPE,
						Images.ImageColumns.BUCKET_ID,
						Images.ImageColumns.BUCKET_DISPLAY_NAME,
						MediaColumns.DATA,
						Images.ImageColumns.DATE_TAKEN
					};

					String title = null;
					String displayName = null;
					String mimeType = null;
					String bucketId = null;
					String bucketDisplayName = null;
					String dataPath = null;
					String dateTaken = null;

					Cursor c = null;
					boolean isDataValid = true;
					boolean isQueriedPhotoValid = true;
					Uri uriData = data.getData();
					if (uriData != null) {
						c = activity.getContentResolver().query(uriData, projection, null, null, null);
					}
					if (c == null) {
						c = activity.getContentResolver().query(Images.Media.EXTERNAL_CONTENT_URI, projection, null, null,
							Images.ImageColumns.DATE_TAKEN);
						isDataValid = false;
					}
					if (c != null) {
						try {
							boolean isCursorValid = false;
							if (uriData != null && isDataValid) {
								isCursorValid = c.moveToNext();
							} else {
								isCursorValid = c.moveToLast();
							}
							if (isCursorValid) {
								title = c.getString(0);
								displayName = c.getString(1);
								mimeType = c.getString(2);
								bucketId = c.getString(3);
								bucketDisplayName = c.getString(4);
								dataPath = c.getString(5);
								dateTaken = c.getString(6);
								
								if (!isDataValid && dateTaken.equals(dateTaken_lastImageInExternalContentURI)) {
									isQueriedPhotoValid = false;
								}

								Log.d(TAG, "Image { title: " + title + " displayName: " + displayName + " mimeType: "
									+ mimeType + " bucketId: " + bucketId + " bucketDisplayName: " + bucketDisplayName
									+ " path: " + dataPath + " }", Log.DEBUG_MODE);
							}
						} finally {
							c.close();
							c = null;
						}
					} else {
						// If we can't get query the image, process it from the imageFile
						processImage(activity);
						return;
					}
					if (!isQueriedPhotoValid) {
						// If the queried image is not the one we just captured, process it from the imageFile
						processImage(activity);
						return;
					}

					String localImageUrl = dataPath;
					URL url;
					try {
						if (!saveToPhotoGallery) {
							// We need to move the image from dataPath to the temp file which will be deleted
							// when the app exits.
							url = new URL(imageUrl);
							moveImage(dataPath, url.getPath());

							// Delete the saved the image entry from the gallery DB.
							if (uriData != null && isDataValid) {
								activity.getContentResolver().delete(uriData, null, null);
							} else {
								activity.getContentResolver().delete(Images.Media.EXTERNAL_CONTENT_URI, "datetaken = ?", new String[] { dateTaken });
							}

							localImageUrl = imageUrl; // make sure it's a good URL before setting it to pass back.
						} else if (imageUrl != null) {
							// Delete the temp file since we want to use the one from the photo gallery
							url = new URL(imageUrl);
							File source = new File(url.getPath());
							source.delete();
						}
					} catch (MalformedURLException e) {
						Log.e(TAG, "Invalid URL not moving image: " + e.getMessage());

					}
					invokeSuccessCallback(activity, localImageUrl);
				}
			}
		}

		private void moveImage(String source, String dest)
		{
			try {

				BufferedInputStream bis = null;
				BufferedOutputStream bos = null;

				File src = new File(source);
				File dst = new File(dest);

				try {
					bis = new BufferedInputStream(new FileInputStream(src), 8096);
					bos = new BufferedOutputStream(new FileOutputStream(dst), 8096);

					byte[] buf = new byte[8096];
					int len = 0;

					while ((len = bis.read(buf)) != -1) {
						bos.write(buf, 0, len);
					}

				} finally {
					if (bis != null) {
						bis.close();
					}

					if (bos != null) {
						bos.close();
					}
				}
				src.delete();

			} catch (IOException e) {
				Log.e(TAG, "Unable to move file: " + e.getMessage(), e);
			}
		}

		@SuppressLint("DefaultLocale")
		private void processImage(Activity activity)
		{
			String localUrl = imageUrl;
			String localPath = imageFile.getAbsolutePath();

			if (saveToPhotoGallery) {
				ContentValues values = new ContentValues(7);
				values.put(Images.Media.TITLE, imageFile.getName());
				values.put(Images.Media.DISPLAY_NAME, imageFile.getName());
				values.put(Images.Media.DATE_TAKEN, new Date().getTime());
				values.put(Images.Media.MIME_TYPE, "image/jpeg");

				File rootsd = Environment.getExternalStorageDirectory();
				localPath = rootsd.getAbsolutePath() + "/dcim/Camera/" + imageFile.getName();
				values.put(Images.ImageColumns.BUCKET_ID, localPath.toLowerCase().hashCode());
				values.put(Images.ImageColumns.BUCKET_DISPLAY_NAME, "Camera");
				moveImage(imageFile.getAbsolutePath(), localPath);
				localUrl = "file://" + localPath;

				values.put("_data", localPath);

				activity.getContentResolver().insert(Images.Media.EXTERNAL_CONTENT_URI, values);

				// puts newly captured photo into the gallery
				MediaScannerClient mediaScanner = new MediaScannerClient(activity, new String[] { localUrl }, null, null);
				mediaScanner.scan();
			}
			invokeSuccessCallback(activity, localPath);
		}

		private void invokeSuccessCallback(Activity activity, String localImageUrl)
		{
			try {
				if (successCallback != null) {
					invokeCallback((TiBaseActivity) activity, successCallback, getKrollObject(), createDictForImage(localImageUrl, "image/jpeg"));
				}

			} catch (OutOfMemoryError e) {
				String msg = "Not enough memory to get image: " + e.getMessage();
				Log.e(TAG, msg);
				if (errorCallback != null) {
					invokeCallback((TiBaseActivity) activity, errorCallback, getKrollObject(), createErrorResponse(UNKNOWN_ERROR, msg));
				}
			}
		}

		@Override
		public void onError(Activity activity, int requestCode, Exception e) {
			if (imageFile != null) {
				imageFile.delete();
			}
			String msg = "Camera problem: " + e.getMessage();
			Log.e(TAG, msg, e);
			if (errorCallback != null) {
				errorCallback.callAsync(getKrollObject(), createErrorResponse(UNKNOWN_ERROR, msg));
			}
		}
	}

	@Kroll.method
	public void openPhotoGallery(KrollDict options)
	{
		KrollFunction successCallback = null;
		KrollFunction cancelCallback = null;
		KrollFunction errorCallback = null;

		if (options.containsKey("success")) {
			successCallback = (KrollFunction) options.get("success");
		}
		if (options.containsKey("cancel")) {
			cancelCallback = (KrollFunction) options.get("cancel");
		}
		if (options.containsKey("error")) {
			errorCallback = (KrollFunction) options.get("error");
		}

		final KrollFunction fSuccessCallback = successCallback;
		final KrollFunction fCancelCallback = cancelCallback;
		final KrollFunction fErrorCallback = errorCallback;

		Log.d(TAG, "openPhotoGallery called", Log.DEBUG_MODE);

		Activity activity = TiApplication.getInstance().getCurrentActivity();
		TiActivitySupport activitySupport = (TiActivitySupport) activity;

		TiIntentWrapper galleryIntent = new TiIntentWrapper(new Intent());
		galleryIntent.getIntent().setAction(Intent.ACTION_PICK);
		galleryIntent.getIntent().setType("image/*");
		galleryIntent.getIntent().addCategory(Intent.CATEGORY_DEFAULT);
		galleryIntent.setWindowId(TiIntentWrapper.createActivityName("GALLERY"));

		final int code = activitySupport.getUniqueResultCode();
		 activitySupport.launchActivityForResult(galleryIntent.getIntent(), code,
			new TiActivityResultHandler() {

				public void onResult(Activity activity, int requestCode, int resultCode, Intent data)
				{
					Log.e(TAG, "OnResult called: " + resultCode);
					if (resultCode == Activity.RESULT_CANCELED) {
						if (fCancelCallback != null) {
							KrollDict response = new KrollDict();
							response.putCodeAndMessage(NO_ERROR, null);
							fCancelCallback.callAsync(getKrollObject(), response);
						}

					} else {
						String path = data.getDataString();
						try {
							if (fSuccessCallback != null) {
								fSuccessCallback.callAsync(getKrollObject(), createDictForImage(path, "image/jpeg"));
							}

						} catch (OutOfMemoryError e) {
							String msg = "Not enough memory to get image: " + e.getMessage();
							Log.e(TAG, msg);
							if (fErrorCallback != null) {
								fErrorCallback.callAsync(getKrollObject(), createErrorResponse(UNKNOWN_ERROR, msg));
							}
						}
					}
				}

				public void onError(Activity activity, int requestCode, Exception e)
				{
					String msg = "Gallery problem: " + e.getMessage();
					Log.e(TAG, msg, e);
					if (fErrorCallback != null) {
						fErrorCallback.callAsync(getKrollObject(), createErrorResponse(UNKNOWN_ERROR, msg));
					}
				}
			});
	}

	public static KrollDict createDictForImage(String path, String mimeType) {
		String[] parts = { path };
		TiBlob imageData = TiBlob.blobFromFile(TiFileFactory.createTitaniumFile(parts, false), mimeType);
		return createDictForImage(imageData, mimeType);
	}

	public static KrollDict createDictForImage(TiBlob imageData, String mimeType) {
		KrollDict d = new KrollDict();
		d.putCodeAndMessage(NO_ERROR, null);

		int width = -1;
		int height = -1;

		BitmapFactory.Options opts = new BitmapFactory.Options();
		opts.inJustDecodeBounds = true;

		// We only need the ContentResolver so it doesn't matter if the root or current activity is used for
		// accessing it
		BitmapFactory.decodeStream(imageData.getInputStream(), null, opts);

		width = opts.outWidth;
		height = opts.outHeight;

		d.put("x", 0);
		d.put("y", 0);
		d.put("width", width);
		d.put("height", height);

		KrollDict cropRect = new KrollDict();
		cropRect.put("x", 0);
		cropRect.put("y", 0);
		cropRect.put("width", width);
		cropRect.put("height", height);
		d.put("cropRect", cropRect);

		d.put("mediaType", MEDIA_TYPE_PHOTO);
		d.put("media", imageData);

		return d;
	}

	KrollDict createDictForImage(int width, int height, byte[] data) {
		KrollDict d = new KrollDict();

		d.put("x", 0);
		d.put("y", 0);
		d.put("width", width);
		d.put("height", height);

		KrollDict cropRect = new KrollDict();
		cropRect.put("x", 0);
		cropRect.put("y", 0);
		cropRect.put("width", width);
		cropRect.put("height", height);
		d.put("cropRect", cropRect);
		d.put("mediaType", MEDIA_TYPE_PHOTO);
		d.put("media", TiBlob.blobFromData(data, "image/png"));

		return d;
	}

	@Kroll.method
	public void previewImage(KrollDict options)
	{
		Activity activity = TiApplication.getAppCurrentActivity();
		if (activity == null) {
			Log.w(TAG, "Unable to get current activity for previewImage.", Log.DEBUG_MODE);
			return;
		}

		KrollFunction successCallback = null;
		KrollFunction errorCallback = null;
		TiBlob image = null;

		if (options.containsKey("success")) {
			successCallback = (KrollFunction) options.get("success");
		}
		if (options.containsKey("error")) {
			errorCallback = (KrollFunction) options.get("error");
		}
		if (options.containsKey("image")) {
			image = (TiBlob) options.get("image");
		}

		if (image == null) {
			if (errorCallback != null) {
				errorCallback.callAsync(getKrollObject(), createErrorResponse(UNKNOWN_ERROR, "Missing image property"));
			}
		}

		TiBaseFile f = (TiBaseFile) image.getData();

		final KrollFunction fSuccessCallback = successCallback;
		final KrollFunction fErrorCallback = errorCallback;

		Log.d(TAG, "openPhotoGallery called", Log.DEBUG_MODE);

		TiActivitySupport activitySupport = (TiActivitySupport) activity;

		Intent intent = new Intent(Intent.ACTION_VIEW);
		TiIntentWrapper previewIntent = new TiIntentWrapper(intent);
		String mimeType = image.getMimeType();

		if (mimeType != null && mimeType.length() > 0) {
			intent.setDataAndType(Uri.parse(f.nativePath()), mimeType);
		} else {
			intent.setData(Uri.parse(f.nativePath()));
		}

		previewIntent.setWindowId(TiIntentWrapper.createActivityName("PREVIEW"));

		final int code = activitySupport.getUniqueResultCode();
		 activitySupport.launchActivityForResult(intent, code,
			new TiActivityResultHandler() {

				public void onResult(Activity activity, int requestCode, int resultCode, Intent data)
				{
					Log.e(TAG, "OnResult called: " + resultCode);
					if (fSuccessCallback != null) {
						KrollDict response = new KrollDict();
						response.putCodeAndMessage(NO_ERROR, null);
						fSuccessCallback.callAsync(getKrollObject(), response);
					}
				}

				public void onError(Activity activity, int requestCode, Exception e)
				{
					String msg = "Gallery problem: " + e.getMessage();
					Log.e(TAG, msg, e);
					if (fErrorCallback != null) {
						fErrorCallback.callAsync(getKrollObject(), createErrorResponse(UNKNOWN_ERROR, msg));
					}
				}
			});
	}

	@Kroll.method
	public void takeScreenshot(KrollFunction callback)
	{
		Activity a = TiApplication.getAppCurrentActivity();

		if (a == null) {
			Log.w(TAG, "Could not get current activity for takeScreenshot.", Log.DEBUG_MODE);
			callback.callAsync(getKrollObject(), new Object[] { null });
			return;
		}

		while (a.getParent() != null) {
			a = a.getParent();
		}

		Window w = a.getWindow();

		while (w.getContainer() != null) {
			w = w.getContainer();
		}

		KrollDict image = TiUIHelper.viewToImage(null, w.getDecorView());
		if (callback != null) {
			callback.callAsync(getKrollObject(), new Object[] { image });
		}
	}

	@Kroll.method
	public void takePicture()
	{
		// make sure the preview / camera are open before trying to take photo
		if (TiCameraActivity.cameraActivity != null) {
			TiCameraActivity.takePicture();
		} else {
			Log.e(TAG, "Camera preview is not open, unable to take photo");
		}
	}

	@Kroll.method
	public void switchCamera(int whichCamera)
	{
		TiCameraActivity activity = TiCameraActivity.cameraActivity;

		if (activity == null || !activity.isPreviewRunning()) {
			Log.e(TAG, "Camera preview is not open, unable to switch camera.");
			return;
		}

		activity.switchCamera(whichCamera);
	}

	@Kroll.method
	@Kroll.getProperty
	public boolean getIsCameraSupported()
	{
		return Camera.getNumberOfCameras() > 0;
	}

	@Kroll.method
	@Kroll.getProperty
	public int[] getAvailableCameras()
	{
		int cameraCount = Camera.getNumberOfCameras();
		if (cameraCount == 0) {
			return null;
		}

		int[] result = new int[cameraCount];

		CameraInfo cameraInfo = new CameraInfo();

		for (int i = 0; i < cameraCount; i++) {
			Camera.getCameraInfo(i, cameraInfo);
			switch (cameraInfo.facing) {
				case CameraInfo.CAMERA_FACING_FRONT:
					result[i] = CAMERA_FRONT;
					break;
				case CameraInfo.CAMERA_FACING_BACK:
					result[i] = CAMERA_REAR;
					break;
				default:
					// This would be odd. As of API level 17,
					// there are just the two options.
					result[i] = -1;
			}
		}

		return result;

	}

	@Override
	public String getApiName()
	{
		return "Ti.Media";
	}
}

