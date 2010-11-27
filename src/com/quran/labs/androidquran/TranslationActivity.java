package com.quran.labs.androidquran;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.database.Cursor;
import android.database.SQLException;
import android.os.AsyncTask;
import android.os.AsyncTask.Status;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.text.Html;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageButton;
import android.widget.TextView;

import com.quran.labs.androidquran.common.GestureQuranActivity;
import com.quran.labs.androidquran.data.ApplicationConstants;
import com.quran.labs.androidquran.data.QuranInfo;
import com.quran.labs.androidquran.util.DatabaseHandler;
import com.quran.labs.androidquran.util.QuranSettings;
import com.quran.labs.androidquran.util.QuranUtils;

public class TranslationActivity extends GestureQuranActivity implements OnInitListener {

	private int page = 1;
    private AsyncTask<?, ?, ?> currentTask;
    private ProgressDialog pd = null;
    private TextView txtTranslation;
    private TextToSpeech tts;
    private ImageButton btnSpeak;
	private boolean speakerEnabled = false;
	
	@Override
	protected void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		setContentView(R.layout.quran_translation);
		txtTranslation = (TextView) findViewById(R.id.translationText);
		btnSpeak = (ImageButton) findViewById(R.id.btnSpeak);
		loadPageState(savedInstanceState);
		gestureDetector = new GestureDetector(new QuranGestureDetector());
		adjustTextSize();
		renderTranslation();
		checkTtsSupport();
		btnSpeak.setOnClickListener(new OnClickListener() {
			
			@Override
			public void onClick(View v) {
				if (tts != null && speakerEnabled) {
					tts.speak(txtTranslation.getText().toString(), TextToSpeech.QUEUE_ADD, null);
				}
			}
		});
	}
	
	private void checkTtsSupport() {
		Intent checkIntent = new Intent();
		checkIntent.setAction(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA);
		startActivityForResult(checkIntent, ApplicationConstants.TTS_CHECK_CODE);
	}
	
	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode == ApplicationConstants.TTS_CHECK_CODE) {
	        if (resultCode == TextToSpeech.Engine.CHECK_VOICE_DATA_PASS) {
	            // success, create the TTS instance
	            tts = new TextToSpeech(this, this);
	        } else {
	            // missing data, install it
	            Intent installIntent = new Intent();
	            installIntent.setAction(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
	            startActivity(installIntent);
	        }
	    }
		super.onActivityResult(requestCode, resultCode, data);
	}
	
	private void adjustTextSize() {
		QuranSettings.load(PreferenceManager.getDefaultSharedPreferences(getApplicationContext()));
		txtTranslation.setTextSize(QuranSettings.getInstance().getTranslationTextSize());
	}
	
	@Override
	protected void onResume() {
		adjustTextSize();
		super.onResume();
	}

	public void goToNextPage(){
		if (page < ApplicationConstants.PAGES_LAST){
			page++;
			renderTranslation();
		}	
	}
	
	public void goToPreviousPage(){
		if (page > ApplicationConstants.PAGES_FIRST){
			page--;
			renderTranslation();
		}	
	}
	
	public void loadPageState(Bundle savedInstanceState){
		page = savedInstanceState != null ? savedInstanceState.getInt("page") : QuranSettings.getInstance().getLastPage();
		if (page == ApplicationConstants.PAGES_FIRST){
			Bundle extras = getIntent().getExtras();
			page = extras != null? extras.getInt("page") : ApplicationConstants.PAGES_FIRST;
		}
		return;
	}
	
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT){
			goToNextPage();
		}
		else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT){
			goToPreviousPage();
		}
		else if (keyCode == KeyEvent.KEYCODE_BACK){
			goBack();
		}

		return super.onKeyDown(keyCode, event);
	}

	public void renderTranslation(){
		if ((page > ApplicationConstants.PAGES_LAST) || (page < ApplicationConstants.PAGES_FIRST)) page = 1;
		setTitle(QuranInfo.getPageTitle(page));

		Integer[] bounds = QuranInfo.getPageBounds(page);
		
		String[] translationLists = new String[]{ "en_si" };
		List<String> unavailable = new ArrayList<String>();
		
		int available = 0;
		List<Map<String, String>> translations = new ArrayList<Map<String, String>>();
		for (String tl : translationLists){
			Map<String, String> currentTranslation = getVerses(tl, bounds);
			if (currentTranslation != null){
				translations.add(currentTranslation);
				available++;
			}
			else {
				unavailable.add(tl);
				translations.add(null);
			}
		}
		
		TextView translationArea = (TextView)findViewById(R.id.translationText);
		translationArea.setText("");
		
		if (available == 0){
			promptForTranslationDownload(unavailable);
			translationArea.setText(R.string.translationsNeeded);
			return;
		}
		
		
		int numTranslations = translationLists.length;
		
		int i = bounds[0];
		for (; i <= bounds[2]; i++){
			int j = (i == bounds[0])? bounds[1] : 1;
			
			for (;;){
				int numAdded = 0;
				String key = i + ":" + j++;
				for (int t = 0; t < numTranslations; t++){
					if (translations.get(t) == null) continue;
					String text = translations.get(t).get(key);
					if (text != null){
						numAdded++;
						String str = "<b>" + key + ":</b> " + text + "<br>";
						translationArea.append(Html.fromHtml(str));
					}
				}
				if (numAdded == 0) break;
			}
		}
	}
	
	public Map<String, String> getVerses(String translation, Integer[] bounds){
		DatabaseHandler handler = null;
		try {
			Map<String, String> ayahs = new HashMap<String, String>();
			handler = new DatabaseHandler(translation);
			for (int i = bounds[0]; i <= bounds[2]; i++){
				int max = (i == bounds[2])? bounds[3] : QuranInfo.getNumAyahs(i);
				int min = (i == bounds[0])? bounds[1] : 1;
				Cursor res = handler.getVerses(i, min, max);
				if ((res == null) || (!res.moveToFirst())) continue;
				do {
					int sura = res.getInt(0);
					int ayah = res.getInt(1);
					String text = res.getString(2);
					ayahs.put(sura + ":" + ayah, text);
				}
				while (res.moveToNext());
			}
			handler.closeDatabase();
			return ayahs;
		}
		catch (SQLException ex){
			ex.printStackTrace();
			if (handler != null) handler.closeDatabase();
			return null;
		}
	}
	
	public void goBack(){
		Intent i = new Intent();
		i.putExtra("page", page);
		setResult(RESULT_OK, i);
		finish();	
	}
	
	public void startDownload(List<String> whatToGet){
		pd = ProgressDialog.show(this, "Downloading..", "Please Wait...", true, true,
				new OnCancelListener(){

					@Override
					public void onCancel(DialogInterface dialog) {
						cancelDownload();
					}
			
		});
		currentTask = new DownloadTranslationsTask().execute(whatToGet.toArray());
	}
	
	public void cancelDownload(){
		pd.dismiss();
		currentTask.cancel(true);
		goBack();
	}
	
	public void doneDownloading(Integer downloaded){
		pd.dismiss();
		if (downloaded > 0) renderTranslation();
		else goBack();
	}
	
	private class DownloadTranslationsTask extends AsyncTask<Object[], Void, Integer> {
    	public Integer doInBackground(Object[]... params){
    		Integer numDownloads = 0;
    		
    		Object[] translations = (Object[]) params[0];
    		for (Object dbName : translations){
    			String tlFile = "quran." + (String)dbName + ".db";
    			if (QuranUtils.getTranslation(tlFile))
    				numDownloads++;
    		}
    		return numDownloads;
    	}
    	    	
    	@Override
    	public void onPostExecute(Integer downloaded){
    		currentTask = null;
    		doneDownloading(downloaded);
    	}
    }
	
	@Override
	protected void onSaveInstanceState(Bundle outState){
		super.onSaveInstanceState(outState);
		outState.putInt("page", page);
	}
	
	@Override
	protected void onDestroy(){
		super.onDestroy();
		if ((currentTask != null) && (currentTask.getStatus() == Status.RUNNING))
			currentTask.cancel(true);
		
		if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
	}
	
	public void promptForTranslationDownload(final List<String> translationsToGet){
    	AlertDialog.Builder dialog = new AlertDialog.Builder(this);
    	dialog.setMessage(R.string.downloadTranslationPrompt);
    	dialog.setCancelable(false);
    	dialog.setPositiveButton(R.string.downloadPrompt_ok,
			new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {
					dialog.cancel();
					startDownload(translationsToGet);
				}
    	});
    	
    	dialog.setNegativeButton(R.string.downloadPrompt_no, 
    			new DialogInterface.OnClickListener() {
    				public void onClick(DialogInterface dialog, int id) {
    					dialog.cancel();
    					goBack();
    				}
    	});
    	
    	AlertDialog alert = dialog.create();
    	alert.setTitle(R.string.downloadPrompt_title);
    	alert.show();
	}

	// Implements TextToSpeech.OnInitListener.
	// http://developer.android.com/resources/samples/ApiDemos/src/com/example/android/apis/app/TextToSpeechActivity.html
	public void onInit(int status) {
		// status can be either TextToSpeech.SUCCESS or TextToSpeech.ERROR.
		if (status == TextToSpeech.SUCCESS) {
			// Set preferred language to US english.
			// Note that a language may not be available, and the result will
			// indicate this.
			int result = tts.setLanguage(Locale.US);
			// Try this someday for some interesting results.
			// int result mTts.setLanguage(Locale.FRANCE);
			if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
				// Lanuage data is missing or the language is not supported.
				Log.e(ApplicationConstants.EXCEPTION_TAG, "Language is not available.");
				btnSpeak.setEnabled(false);
				btnSpeak.setVisibility(ImageButton.INVISIBLE);
			} else {
				// Check the documentation for other possible result codes.
				// For example, the language may be available for the locale,
				// but not for the specified country and variant.

				// The TTS engine has been successfully initialized.
				// Allow the user to press the button for the app to speak
				// again.
				btnSpeak.setEnabled(true);
				btnSpeak.setVisibility(ImageButton.VISIBLE);
				speakerEnabled  = true;
			}
		} else {
			// Initialization failed.
			Log.e(ApplicationConstants.EXCEPTION_TAG, "Could not initialize TextToSpeech.");
		}
	}
}
