package io.github.sspanak.tt9;

import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.Toast;

import io.github.sspanak.tt9.LangHelper.LANGUAGE;
import io.github.sspanak.tt9.Utils.SpecialInputType;
import io.github.sspanak.tt9.db.T9DB;
import io.github.sspanak.tt9.preferences.T9Preferences;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

public class TraditionalT9 extends InputMethodService {
	private CandidateView mCandidateView;
	private InterfaceHandler interfacehandler = null;

	private StringBuilder mComposing = new StringBuilder();
	private StringBuilder mComposingI = new StringBuilder();

	private ArrayList<String> mSuggestionStrings = new ArrayList<String>(10);
	private ArrayList<Integer> mSuggestionInts = new ArrayList<Integer>(10);
	private AbstractList<String> mSuggestionSym = new ArrayList<String>(16);

	private static final int NON_EDIT = 0;
	private static final int EDITING = 1;
	private static final int EDITING_NOSHOW = 2;
	private int mEditing = NON_EDIT;

	private boolean mGaveUpdateWarn = false;

	private boolean mFirstPress = false;

	private boolean mIgnoreDPADKeyUp = false;
	private KeyEvent mDPADkeyEvent = null;

	protected boolean mWordFound = true;

	private AbsSymDialog mSymbolPopup = null;
	private AbsSymDialog mSmileyPopup = null;
	protected boolean mAddingWord = false;
	// private boolean mAddingSkipInput = false;
	private int mPrevious;
	private int mCharIndex;

	private String mPreviousWord = "";

	private int mCapsMode;
	private LANGUAGE mLang;
	private int mLangIndex;

	private LANGUAGE[] mLangsAvailable = null;

	private final static int[] CAPS_CYCLE = { T9Preferences.CASE_LOWER, T9Preferences.CASE_CAPITALIZE, T9Preferences.CASE_UPPER };

	private final static int T9DELAY = 900;
	final Handler t9releasehandler = new Handler();
	Runnable mt9release = new Runnable() {
		@Override
		public void run() {
			commitReset();
		}
	};

	private T9DB db;
	private T9Preferences prefs;

	private static final int[] MODE_CYCLE = { T9Preferences.MODE_PREDICTIVE, T9Preferences.MODE_ABC, T9Preferences.MODE_123 };
	private int mKeyMode;

	private InputConnection currentInputConnection = null;

	/**
	 * Main initialization of the input method component. Be sure to call to
	 * super class.
	 */
	@Override
	public void onCreate() {
		super.onCreate();
		mPrevious = -1;
		mCharIndex = 0;
		db = T9DB.getInstance(this);
		prefs = new T9Preferences(this);

		if (interfacehandler == null) {
			interfacehandler = new InterfaceHandler(getLayoutInflater().inflate(R.layout.mainview,
					null), this);
		}
	}

	@Override
	public boolean onEvaluateInputViewShown() {
		//Log.d("T9.onEvaluateInputViewShown", "whatis");
		//Log.d("T9.onEval", "fullscreen?: " + isFullscreenMode() + " isshow?: " + isInputViewShown() + " isrequestedshow?: " + isShowInputRequested());
		if (mEditing == EDITING_NOSHOW) {
			return false;
		}
		// TODO: Verify if need this:
//		if (interfacehandler != null) {
//			interfacehandler.showView();
//		}
		return true;
	}

	/**
	 * Called by the framework when your view for creating input needs to be
	 * generated. This will be called the first time your input method is
	 * displayed, and every time it needs to be re-created such as due to a
	 * configuration change.
	 */
	@Override
	public View onCreateInputView() {
		View v = getLayoutInflater().inflate(R.layout.mainview, null);
		interfacehandler.changeView(v);
		if (mKeyMode == T9Preferences.MODE_PREDICTIVE) {
			interfacehandler.showHold(true);
		} else {
			interfacehandler.showHold(false);
		}
		return v;
	}

	/**
	 * Called by the framework when your view for showing candidates needs to be
	 * generated, like {@link #onCreateInputView}.
	 */
	@Override
	public View onCreateCandidatesView() {
		mCandidateView = new CandidateView(this);
		return mCandidateView;
	}

	protected void showSymbolPage() {
		if (mSymbolPopup == null) {
			mSymbolPopup = new SymbolDialog(this, getLayoutInflater().inflate(R.layout.symbolview,
					null));
		}
		mSymbolPopup.doShow(getWindow().getWindow().getDecorView());
	}

	protected void showSmileyPage() {
		if (mSmileyPopup == null) {
			mSmileyPopup = new SmileyDialog(this, getLayoutInflater().inflate(R.layout.symbolview,
					null));
		}
		mSmileyPopup.doShow(getWindow().getWindow().getDecorView());
	}

	private void clearState() {
		mSuggestionStrings.clear();
		mSuggestionInts.clear();
		mSuggestionSym.clear();
		mPreviousWord = "";
		mComposing.setLength(0);
		mComposingI.setLength(0);
		mWordFound = true;
	}

	private String getSurroundingWord() {
		CharSequence before = currentInputConnection.getTextBeforeCursor(50, 0);
		CharSequence after = currentInputConnection.getTextAfterCursor(50, 0);
		int bounds = -1;
		if (!TextUtils.isEmpty(before)) {
			bounds = before.length() -1;
			while (bounds > 0 && !Character.isWhitespace(before.charAt(bounds))) {
				bounds--;
			}
			before = before.subSequence(bounds, before.length());
		}
		if (!TextUtils.isEmpty(after)) {
			bounds = 0;
			while (bounds < after.length() && !Character.isWhitespace(after.charAt(bounds))) {
				bounds++;
			}
			after = after.subSequence(0, bounds);
		}
		return before.toString().trim() + after.toString().trim();
	}

	protected void showAddWord() {
		if (mKeyMode == T9Preferences.MODE_PREDICTIVE) {
			// decide if we are going to look for work to base on
			String template = mComposing.toString();
			if (template.length() == 0) {
				//get surrounding word:
				template = getSurroundingWord();
			}
			Log.d("showAddWord", "WORD: "+template);
			Intent awintent = new Intent(this, AddWordAct.class);
			awintent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			awintent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
			awintent.putExtra("io.github.sspanak.tt9.word", template);
			awintent.putExtra("io.github.sspanak.tt9.lang", mLang.id);
			clearState();
			currentInputConnection.setComposingText("", 0);
			currentInputConnection.finishComposingText();
			updateCandidates();
			//onFinishInput();
			mWordFound = true;
			startActivity(awintent);
		}
	}

	// sanitize lang and set index for cycling lang
	// Need to check if last lang is available, if not, set index to -1 and set lang to default to 0
	private LANGUAGE sanitizeLang(LANGUAGE lang) {
		mLangIndex = 0;
		if (mLangsAvailable.length < 1 || lang == LANGUAGE.NONE) {
			Log.e("T9.sanitizeLang", "This shouldn't happen.");
			return mLangsAvailable[0];
		}
		else {
			int index = LangHelper.findIndex(mLangsAvailable, lang);
			mLangIndex = index;
			return mLangsAvailable[index];
		}
	}
	/**
	 * This is the main point where we do our initialization of the input method
	 * to begin operating on an application. At this point we have been bound to
	 * the client, and are now receiving all of the detailed information about
	 * the target of our edits.
	 */
	@Override
	public void onStartInput(EditorInfo inputField, boolean restarting) {
		currentInputConnection = getCurrentInputConnection();
		// Log.d("T9.onStartInput", "INPUTTYPE: " + inputField.inputType + " FIELDID: " + inputField.fieldId +
		// 	" FIELDNAME: " + inputField.fieldName + " PACKAGE NAME: " + inputField.packageName);

		// https://developer.android.com/reference/android/text/InputType#TYPE_NULL
		// Special or limited input type. This means the input connection is not rich,
		// or it can not process or show things like candidate text, nor retrieve the current text.
		// We just let Android handle this input.
		if (inputField.inputType == InputType.TYPE_NULL) {
			mLang = null;
			mEditing = NON_EDIT;
			requestHideSelf(0);
			hideStatusIcon();
			return;
		}

		// Reset our state. We want to do this even if restarting, because
		// the underlying state of the text editor could have changed in any
		// way.
		clearState();

		// get relevant settings
		mLangsAvailable = LangHelper.buildLangs(prefs.getEnabledLanguages());
		mLang = sanitizeLang(LANGUAGE.get(prefs.getInputLanguage()));

		// initialize typing mode
		mFirstPress = true;
		mEditing = isFilterTextField(inputField) ? EDITING_NOSHOW : EDITING;
		mKeyMode = determineInputMode(inputField);

		// show or hide UI elements
		requestShowSelf(1);
		updateCandidates();
		setSuggestions(null, -1);
		setCandidatesViewShown(false);

		// We also want to look at the current state of the editor
		// to decide whether our alphabetic keyboard should start out
		// shifted.
		if (mKeyMode != T9Preferences.MODE_123) {
			updateTextCase(inputField);
		}

		updateStatusIcon();

		// handle word adding
		if (inputField.privateImeOptions != null && inputField.privateImeOptions.equals("io.github.sspanak.tt9.addword=true")) {
			mAddingWord = true;
		} else {
			restoreLastWordIfAny();
		}
	}

	/**
	 * This is called when the user is done editing a field. We can use this to
	 * reset our state.
	 */
	@Override
	public void onFinishInput() {
		super.onFinishInput();
		// Log.d("onFinishInput", "When is this called?");
		if (mEditing == EDITING || mEditing == EDITING_NOSHOW) {
			prefs.setInputLanguage(mLang.id);
			commitTyped();
			finish();
		}
	}

	// @Override public void onFinishInputView (boolean finishingInput) {
	// Log.d("onFinishInputView", "? " + finishingInput);
	// }

	private void finish() {
		// Log.d("finish", "why?");
		// Clear current composing text and candidates.
		pickSelectedCandidate(currentInputConnection);
		clearState();
		// updateCandidates();

		// We only hide the candidates window when finishing input on
		// a particular editor, to avoid popping the underlying application
		// up and down if the user is entering text into the bottom of
		// its window.
		// setCandidatesViewShown(false);

		// TODO: check this?
		mEditing = NON_EDIT;
		hideWindow();
		hideStatusIcon();
	}

	@Override
	public void onDestroy() {
		db.close();
		super.onDestroy();
	}

	// @Override public void onStartInputView(EditorInfo attribute, boolean
	// restarting) {
	// Log.d("onStartInputView", "attribute.inputType: " + attribute.inputType +
	// " restarting? " + restarting);
	// //super.onStartInputView(attribute, restarting);
	// }

	/**
	 * Deal with the editor reporting movement of its cursor.
	 */
	@Override
	public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
								  int candidatesStart, int candidatesEnd) {
		super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart,
				candidatesEnd);
		if (mKeyMode == T9Preferences.MODE_ABC) { return; } // stops the ghost fast-type commit
		// If the current selection in the text view changes, we should
		// clear whatever candidate text we have.
		if ((mComposing.length() > 0 || mComposingI.length() > 0)
				&& (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
			mComposing.setLength(0);
			mComposingI.setLength(0);
			updateCandidates();
			if (currentInputConnection != null) {
				currentInputConnection.finishComposingText();
			}
		}
	}

	/**
	 * This tells us about completions that the editor has determined based on
	 * the current text in it. We want to use this in fullscreen mode to show
	 * the completions ourself, since the editor can not be seen in that
	 * situation.
	 */
	@Override
	public void onDisplayCompletions(CompletionInfo[] completions) {
		// ??????????????
	}

	/**
	 * Use this to monitor key events being delivered to the application. We get
	 * first crack at them, and can either resume them or let them continue to
	 * the app.
	 */
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		//		Log.d("onKeyDown", "Key: " + event + " repeat?: " +
//				event.getRepeatCount() + " long-time: " + event.isLongPress());
		if (mEditing == NON_EDIT) {
			// // catch for UI weirdness on up event thing
			return false;
		}
		mFirstPress = false;

		if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
			if (interfacehandler != null) {
				interfacehandler.setPressed(keyCode, true);
			} // pass-through


			if (mEditing == EDITING_NOSHOW) {
				return false;
			}
			return handleDPAD(keyCode, event, true);
		} else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
			if (mEditing == EDITING_NOSHOW) {
				return false;
			}
			return handleDPAD(keyCode, event, true);
		} else if (keyCode == KeyEvent.KEYCODE_SOFT_RIGHT || keyCode == KeyEvent.KEYCODE_SOFT_LEFT) {
			if (!isInputViewShown()) {
				return super.onKeyDown(keyCode, event);
			}

		} else if (keyCode == prefs.getKeyBackspace()) {// Special handling of the delete key: if we currently are
			// composing text for the user, we want to modify that instead
			// of let the application do the delete itself.
			// if (mComposing.length() > 0) {
			onKey(keyCode, null);
			return true;
			// }
			// break;
		}

		// only handle first press except for delete
		if (event.getRepeatCount() != 0) {
			return true;
		}
		if (mKeyMode == T9Preferences.MODE_ABC) {
			t9releasehandler.removeCallbacks(mt9release);
		}
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			// handle Back ourselves while typing, so that it can be used to delete text
			// or let it be, when not typing
			return isThereText();
		} else if (keyCode == KeyEvent.KEYCODE_ENTER) {// Let the underlying text editor always handle these.
			return false;

			// special case for softkeys
		} else if (keyCode == KeyEvent.KEYCODE_SOFT_RIGHT || keyCode == KeyEvent.KEYCODE_SOFT_LEFT) {
			if (interfacehandler != null) {
				interfacehandler.setPressed(keyCode, true);
			}
			// pass-through


			event.startTracking();
			return true;
		} else if (keyCode == KeyEvent.KEYCODE_0 || keyCode == KeyEvent.KEYCODE_1 || keyCode == KeyEvent.KEYCODE_2 ||
				keyCode == KeyEvent.KEYCODE_3 || keyCode == KeyEvent.KEYCODE_4 || keyCode == KeyEvent.KEYCODE_5 ||
				keyCode == KeyEvent.KEYCODE_6 || keyCode == KeyEvent.KEYCODE_7 || keyCode == KeyEvent.KEYCODE_8 ||
				keyCode == KeyEvent.KEYCODE_9 || keyCode == KeyEvent.KEYCODE_POUND || keyCode == KeyEvent.KEYCODE_STAR) {
			event.startTracking();
			return true;
		} else {// KeyCharacterMap.load(KeyCharacterMap.BUILT_IN_KEYBOARD).getNumber(keyCode)
			// Log.w("onKeyDown", "Unhandled Key: " + keyCode + "(" +
			// event.toString() + ")");
		}
		Log.w("onKeyDown", "Unhandled Key: " + keyCode + "(" + event.toString() + ")");
		commitReset();
		return super.onKeyDown(keyCode, event);
	}

	protected void launchOptions() {
		Intent awintent = new Intent(this, TraditionalT9Settings.class);
		awintent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		awintent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
		if (interfacehandler != null) {
			interfacehandler.setPressed(KeyEvent.KEYCODE_SOFT_RIGHT, false);
		}
		hideWindow();
		startActivity(awintent);
	}
	@Override
	public boolean onKeyLongPress(int keyCode, KeyEvent event) {
		// consume since we will assume we have already handled the long press
		// if greater than 1
		if (event.getRepeatCount() != 1) {
			return true;
		}

		// Log.d("onLongPress", "LONG PRESS: " + keyCode);
		// HANDLE SPECIAL KEYS
		if (keyCode == KeyEvent.KEYCODE_POUND) {
			commitReset();
			// do default action or insert new line
			if (!sendDefaultEditorAction(true)) {
				onText("\n");
			}
			return true;
		} else if (keyCode == KeyEvent.KEYCODE_STAR) {
			if (mKeyMode != T9Preferences.MODE_123) {
				if (mLangsAvailable.length > 1) {
					nextLang();
				} else {
					showSmileyPage(); // TODO: replace with lang select if lang thing
				}
				return true;
			}

		} else if (keyCode == KeyEvent.KEYCODE_SOFT_LEFT) {
			if (interfacehandler != null) {
				interfacehandler.setPressed(keyCode, false);
			}
			if (mKeyMode == T9Preferences.MODE_PREDICTIVE) {
				if (mWordFound) {
					showAddWord();
				} else {
					showSymbolPage();
				}
			}

		} else if (keyCode == KeyEvent.KEYCODE_SOFT_RIGHT) {
			if (interfacehandler != null) {
				interfacehandler.setPressed(keyCode, false);
			}
			launchOptions();
			// show Options
			return true;
		}
		if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
			if (mKeyMode == T9Preferences.MODE_PREDICTIVE) {
				commitTyped();
				onText(String.valueOf(keyCode - KeyEvent.KEYCODE_0));
			} else if (mKeyMode == T9Preferences.MODE_ABC) {
				commitReset();
				onText(String.valueOf(keyCode - KeyEvent.KEYCODE_0));
			} else if (mKeyMode == T9Preferences.MODE_123) {
				if (keyCode == KeyEvent.KEYCODE_0) {
					onText("+");
				}
			}
		}
		return true;
	}
	/**
	 * Use this to monitor key events being delivered to the application. We get
	 * first crack at them, and can either resume them or let them continue to
	 * the app.
	 */
	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
	//		Log.d("onKeyUp", "Key: " + keyCode + " repeat?: " +
	//			event.getRepeatCount());
		if (mEditing == NON_EDIT) {
			// if (mButtonClose) {
			// //handle UI weirdness on up event
			// mButtonClose = false;
			// return true;
			// }
			// Log.d("onKeyDown", "returned false");
			return false;
		} else if (mFirstPress) {
			// to make sure changing between input UI elements works correctly.
			return super.onKeyUp(keyCode, event);
		}

		if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
			if (interfacehandler != null) {
				interfacehandler.setPressed(keyCode, false);
			}
			if (mEditing == EDITING_NOSHOW) {
				return false;
			}
			return handleDPAD(keyCode, event, false);
		} else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
			if (mEditing == EDITING_NOSHOW) {
				return false;
			}
			return handleDPAD(keyCode, event, false);
		} else if (keyCode == KeyEvent.KEYCODE_SOFT_RIGHT || keyCode == KeyEvent.KEYCODE_SOFT_LEFT) {
			if (!isInputViewShown()) {
				return super.onKeyDown(keyCode, event);
			}
		}

		if (event.isCanceled()) {
			return true;
		}

		if (keyCode == KeyEvent.KEYCODE_BACK) {
			if (isThereText()) {
				handleBackspace();
				return true;
			} else if (isInputViewShown()) {
				hideWindow();
			}

			return false;
		} else if (keyCode == prefs.getKeyBackspace()) {
			return true;
		} else if (keyCode == KeyEvent.KEYCODE_ENTER) {
			return false;

			// special case for softkeys
		} else if (keyCode == KeyEvent.KEYCODE_SOFT_RIGHT || keyCode == KeyEvent.KEYCODE_SOFT_LEFT) {// if (mAddingWord){
			// Log.d("onKeyUp", "key: " + keyCode + " skip: " +
			// mAddingSkipInput);
			// if (mAddingSkipInput) {
			// //mAddingSkipInput = false;
			// return true;
			// }
			// }
			if (interfacehandler != null) {
				interfacehandler.setPressed(keyCode, false);
			}
			// pass-through

			if (!isInputViewShown()) {
				showWindow(true);
			}
			onKey(keyCode, null);
			return true;
		} else if (keyCode == KeyEvent.KEYCODE_0 || keyCode == KeyEvent.KEYCODE_1 || keyCode == KeyEvent.KEYCODE_2
				|| keyCode == KeyEvent.KEYCODE_3 || keyCode == KeyEvent.KEYCODE_4 || keyCode == KeyEvent.KEYCODE_5 ||
				keyCode == KeyEvent.KEYCODE_6 || keyCode == KeyEvent.KEYCODE_7 || keyCode == KeyEvent.KEYCODE_8 ||
				keyCode == KeyEvent.KEYCODE_9 || keyCode == KeyEvent.KEYCODE_POUND || keyCode == KeyEvent.KEYCODE_STAR) {
			// if (!isInputViewShown()){
			// Log.d("onKeyUp", "showing window.");
			// //showWindow(true);
			// }
			if (!isInputViewShown()) {
				showWindow(true);
			}
			onKey(keyCode, null);
			return true;
		} else {// KeyCharacterMap.load(KeyCharacterMap.BUILT_IN_KEYBOARD).getNumber(keyCode)
			Log.w("onKeyUp", "Unhandled Key: " + keyCode + "(" + event.toString() + ")");
		}
		commitReset();
		return super.onKeyUp(keyCode, event);
	}

	/**
	 * Helper function to commit any text being composed in to the editor.
	 */
	// private void commitTyped() { commitTyped(getCurrentInputConnection()); }
	private void commitTyped() {
		if (interfacehandler != null) {
			interfacehandler.showNotFound(false);
		}

		pickSelectedCandidate(currentInputConnection);

		clearState();
		updateCandidates();
		setCandidatesViewShown(false);
	}

	/**
	 * Helper to update the shift state of our keyboard based on the initial
	 * editor state.
	 */
	private void updateTextCase(EditorInfo inputField) {
		// Log.d("updateShift", "CM start: " + mCapsMode);
		if (inputField != null && mCapsMode != T9Preferences.CASE_UPPER) {
			int caps = 0;
			if (inputField.inputType != InputType.TYPE_NULL) {
				caps = currentInputConnection.getCursorCapsMode(inputField.inputType);
			}
			// mInputView.setShifted(mCapsLock || caps != 0);
			// Log.d("updateShift", "caps: " + caps);
			if ((caps & TextUtils.CAP_MODE_CHARACTERS) == TextUtils.CAP_MODE_CHARACTERS) {
				mCapsMode = T9Preferences.CASE_UPPER;
			} else if ((caps & TextUtils.CAP_MODE_SENTENCES) == TextUtils.CAP_MODE_SENTENCES) {
				mCapsMode = T9Preferences.CASE_CAPITALIZE;
			} else if ((caps & TextUtils.CAP_MODE_WORDS) == TextUtils.CAP_MODE_WORDS) {
				mCapsMode = T9Preferences.CASE_CAPITALIZE;
			} else {
				mCapsMode = T9Preferences.CASE_LOWER;
			}
			updateStatusIcon();
		}
		// Log.d("updateShift", "CM end: " + mCapsMode);
	}

	/**
	 * Helper to send a key down / key up pair to the current editor. NOTE: Not
	 * supposed to use this apparently. Need to use it for DEL. For other things
	 * I'll have to onText
	 */
	private void keyDownUp(int keyEventCode) {
		currentInputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyEventCode));
		currentInputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyEventCode));
	}
	private void keyDownUp(String keys) {
		currentInputConnection.sendKeyEvent(new KeyEvent(SystemClock.uptimeMillis(), keys, 0, 0));
	}

	public void onKey(int keyCode, int[] keyCodes) {
		// Log.d("OnKey", "pri: " + keyCode);
		// Log.d("onKey", "START Cm: " + mCapsMode);
		// HANDLE SPECIAL KEYS
		if (keyCode == prefs.getKeyBackspace()) {
			handleBackspace();
		} else if (keyCode == KeyEvent.KEYCODE_STAR) {
			// change case
			if (mKeyMode == T9Preferences.MODE_123) {
				handleCharacter(KeyEvent.KEYCODE_STAR);
			} else {
				handleShift();
			}
		} else if (keyCode == KeyEvent.KEYCODE_BACK) {
			handleClose();
		} else if (keyCode == KeyEvent.KEYCODE_POUND) {
			// space
			handleCharacter(KeyEvent.KEYCODE_POUND);
		} else if (keyCode == KeyEvent.KEYCODE_SOFT_LEFT) {
			if (mWordFound) {
				showSymbolPage();
			} else {
				showAddWord();
			}

		} else if (keyCode == KeyEvent.KEYCODE_SOFT_RIGHT) {
			nextKeyMode();

		} else {
			if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
				handleCharacter(keyCode);
			} else {
				Log.e("onKey", "This shouldn't happen, unknown key");
			}
		}
		// Log.d("onKey", "END Cm: " + mCapsMode);
	}

	public void onText(CharSequence text) {
		if (currentInputConnection == null)
			return;
		currentInputConnection.beginBatchEdit();
		if (mComposing.length() > 0 || mComposingI.length() > 0) {
			commitTyped();
		}
		currentInputConnection.commitText(text, 1);
		currentInputConnection.endBatchEdit();
		updateTextCase(getCurrentInputEditorInfo());
	}

	/**
	 * Used from interface to either close the input UI if not composing text or
	 * to accept the composing text
	 */
	protected void handleMidButton() {
		if (!isInputViewShown()) {
			showWindow(true);
			return;
		}
		if (mComposing.length() > 0) {
			switch (mKeyMode) {
				case T9Preferences.MODE_PREDICTIVE:
					commitTyped();
					break;
				case T9Preferences.MODE_ABC:
					commitTyped();
					charReset();
					break;
				case T9Preferences.MODE_123:
					// shouldn't happen
					break;
			}
		} else {
			hideWindow();
		}
	}

	/**
	 * determineInputMode
	 * Determine the typing mode based on the input field being edited.
	 *
	 * @param  inputField
	 * @return T9Preferences.MODE_ABC | T9Preferences.MODE_123 | T9Preferences.MODE_PREDICTIVE
	 */
	private int determineInputMode(EditorInfo inputField) {
		if (inputField.inputType == SpecialInputType.TYPE_SHARP_007H_PHONE_BOOK) {
			return T9Preferences.MODE_ABC;
		}

		if (inputField.privateImeOptions != null && inputField.privateImeOptions.equals("io.github.sspanak.tt9.addword=true")) {
			return T9Preferences.MODE_ABC;
		}

		switch (inputField.inputType & InputType.TYPE_MASK_CLASS) {
			case InputType.TYPE_CLASS_NUMBER:
			case InputType.TYPE_CLASS_DATETIME:
				// Numbers and dates default to the symbols keyboard, with
				// no extra features.
			case InputType.TYPE_CLASS_PHONE:
				// Phones will also default to the symbols keyboard, though
				// often you will want to have a dedicated phone keyboard.
				return T9Preferences.MODE_123;

			case InputType.TYPE_CLASS_TEXT:
				// This is general text editing. We will default to the
				// normal alphabetic keyboard, and assume that we should
				// be doing predictive text (showing candidates as the
				// user types).

				return isSpecializedTextField(inputField) ? T9Preferences.MODE_ABC : prefs.getInputMode();

			default:
				// For all unknown input types, default to the alphabetic
				// keyboard with no special features.
				return T9Preferences.MODE_ABC;
		}
	}

	private boolean isSpecializedTextField(EditorInfo inputField) {
		int variation = inputField.inputType & InputType.TYPE_MASK_VARIATION;

		return (
				variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
				|| variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
				|| variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
				|| variation == InputType.TYPE_TEXT_VARIATION_URI
				|| variation == InputType.TYPE_TEXT_VARIATION_FILTER
		);
	}

	/**
	 * isFilterTextField
	 * handle filter list cases... do not hijack DPAD center and make sure back's go through proper
	 *
	 * @param  inputField
	 * @return boolean
	 */
	private boolean isFilterTextField(EditorInfo inputField) {
		int inputType = inputField.inputType & InputType.TYPE_MASK_CLASS;
		int inputVariation = inputField.inputType & InputType.TYPE_MASK_VARIATION;

		return inputType == InputType.TYPE_CLASS_TEXT && inputVariation == InputType.TYPE_TEXT_VARIATION_FILTER;
	}

	private void restoreLastWordIfAny() {
		mAddingWord = false;
		String prevword = prefs.getLastWord();
		if (prevword != "") {
			onText(prevword);
			prefs.setLastWord("");
		}
	}

	/**
	 * Update the list of available candidates from the current composing text.
	 * Do a lot of complicated stuffs.
	 */
	private void updateCandidates() {
		updateCandidates(false);
	}
	private void updateCandidates(boolean backspace) {
		if (mKeyMode == T9Preferences.MODE_PREDICTIVE) {
			int len = mComposingI.length();
			if (len > 0) {
				if (mComposingI.charAt(len - 1) == '1') {
					boolean suggestions = !mSuggestionStrings.isEmpty();
					String prefix = "";
					if (mPreviousWord.length() == 0) {
						if (suggestions && !backspace) {
							prefix = mPreviousWord = mSuggestionStrings.get(mCandidateView.mSelectedIndex);
						}
					} else {
						if (backspace) {
							prefix = mPreviousWord;
						} else {
							if (suggestions) {
								if (mCandidateView.mSelectedIndex == -1) { mCandidateView.mSelectedIndex = 0; }
								prefix = mPreviousWord = mSuggestionStrings.get(mCandidateView.mSelectedIndex);
							} else {
								prefix = mPreviousWord;
							}
						}
					}
					mSuggestionInts.clear();
					mSuggestionStrings.clear();
					mSuggestionSym.clear();
					db.updateWords("1", mSuggestionSym, mSuggestionInts, mCapsMode, mLang);
					for (String a : mSuggestionSym) {
						if (!prefix.equals("")) {
							mSuggestionStrings.add(prefix + a);
						} else {
							mSuggestionStrings.add(String.valueOf(a));
							mComposingI.setLength(0);
							mComposingI.append("1");
						}
					}
				} else {
					db.updateWords(mComposingI.toString(), mSuggestionStrings, mSuggestionInts,
							mCapsMode, mLang);
				}
				if (!mSuggestionStrings.isEmpty()) {
					mWordFound = true;
					mComposing.setLength(0);
					mComposing.append(mSuggestionStrings.get(0));
					if (interfacehandler != null) {
						interfacehandler.showNotFound(false);
					}
				} else {
					mWordFound = false;
					mComposingI.setLength(len - 1);
					setCandidatesViewShown(false);
					if (interfacehandler != null) {
						interfacehandler.showNotFound(true);
					}
				}
				setSuggestions(mSuggestionStrings, 0);
				} else {
				setSuggestions(null, -1);
				setCandidatesViewShown(false);
				if (interfacehandler != null) {
					interfacehandler.showNotFound(false);
				}
			}
		} else if (mKeyMode == T9Preferences.MODE_ABC) {
			if (mComposing.length() > 0) {
				//Log.d("updateCandidates", "Previous: " + mComposing.toString());
				mSuggestionStrings.clear();

				char[] ca = CharMap.T9TABLE[mLang.index][mPrevious];
				for (char c : ca) {
					mSuggestionStrings.add(String.valueOf(c));
				}
				setSuggestions(mSuggestionStrings, mCharIndex);
				//Log.d("updateCandidates", "newSuggestedIndex: " + mCharIndex);
			} else {
				setSuggestions(null, -1);
			}
		}
	}

	private void setSuggestions(List<String> suggestions, int initialSel) {
		if (suggestions != null && suggestions.size() > 0) {
			setCandidatesViewShown(true);
		}
		if (mCandidateView != null) {
			mCandidateView.setSuggestions(suggestions, initialSel);
		}
	}

	private void handleBackspace() {
		final int length = mComposing.length();
		final int length2 = mComposingI.length();
		if (mKeyMode == T9Preferences.MODE_ABC) {
			charReset();
			setCandidatesViewShown(false);
		}
		//Log.d("handleBS", "Stage1: (" + length + "," + length2 + ")");
		//Log.d("handleBS", "Stage1: (" + mComposingI.toString() + ")");
		if (length2 > 1) {
			if (mComposingI.charAt(length2 - 1) == '1') {
				// revert previous word
				mPreviousWord = mPreviousWord.substring(0, mPreviousWord.length() - 1);
			}
			mComposingI.delete(length2 - 1, length2);
			if (length2 - 1 > 1) {
				if (mComposingI.charAt(length2 - 2) != '1') {
					if (mComposingI.indexOf("1") == -1) {
						// no longer contains punctuation so we no longer care
						mPreviousWord = "";
					}
				}
			} else {
				mPreviousWord = "";
			}
			updateCandidates(true);
			currentInputConnection.setComposingText(mComposing, 1);
		} else if (length > 0 || length2 > 0) {
			//Log.d("handleBS", "resetting thing");
			mComposing.setLength(0);
			mComposingI.setLength(0);
			interfacehandler.showNotFound(false);
			mSuggestionStrings.clear();
			mPreviousWord = "";
			currentInputConnection.commitText("", 0);
			updateCandidates();
		} else {
			mPreviousWord = "";
			keyDownUp(prefs.getKeyBackspace());
		}
		updateTextCase(getCurrentInputEditorInfo());
		// Log.d("handleBS", "Cm: " + mCapsMode);
		// Why do I need to call this twice, android...
		updateTextCase(getCurrentInputEditorInfo());
	}

	private void handleShift() {
		// do my own thing here
		if (mCapsMode == CAPS_CYCLE.length - 1) {
			mCapsMode = 0;
		} else {
			mCapsMode++;
		}

		if (mKeyMode == T9Preferences.MODE_PREDICTIVE && mComposing.length() > 0) {
			updateCandidates();
			currentInputConnection.setComposingText(mComposing, 1);
		}
		updateStatusIcon();
	}

	/**
	 * handle input of a character. Precondition: ONLY 0-9 AND *# ARE ALLOWED
	 *
	 * @param keyCode
	 */
	private void handleCharacter(int keyCode) {
		switch (mKeyMode) {
			case T9Preferences.MODE_PREDICTIVE:
				// it begins
				if (keyCode == KeyEvent.KEYCODE_POUND || keyCode == KeyEvent.KEYCODE_0) {
					if (mComposing.length() > 0) {
						commitTyped();
					}
					onText(" ");
				} else {
					// do things
					keyCode = keyCode - KeyEvent.KEYCODE_0;
					mComposingI.append(keyCode);
					updateCandidates();
					currentInputConnection.setComposingText(mComposing, 1);
				}
				break;

			case T9Preferences.MODE_ABC:
				t9releasehandler.removeCallbacks(mt9release);
				if (keyCode == KeyEvent.KEYCODE_POUND) {
					keyCode = 10;
				} else {
					keyCode = keyCode - KeyEvent.KEYCODE_0;
				}
				// special translation of that keyCode (which is now T9TABLE index
				if (keyCode == 0)
					keyCode = 11;
				if (keyCode == 10)
					keyCode = 12;
				//Log.d("handleChar", "Key: " + keyCode + "Previous Key: " + mPrevious + " Index:" + mCharIndex);

				boolean newChar = false;
				if (mPrevious == keyCode) {
					mCharIndex++;
				} else {
					//Log.d("handleChar", "COMMITING:" + mComposing.toString());
					commitTyped();
					// updateTextCase(getCurrentInputEditorInfo());
					newChar = true;
					mCharIndex = 0;
					mPrevious = keyCode;
				}

				// start at caps if CapMode
				// Log.d("handleChar", "Cm: " + mCapsMode);
				if (mCharIndex == 0 && mCapsMode != T9Preferences.CASE_LOWER) {
					mCharIndex = CharMap.T9CAPSTART[mLang.index][keyCode];
				}

				mComposing.setLength(0);
				mComposingI.setLength(0);
				char[] ca = CharMap.T9TABLE[mLang.index][keyCode];
				if (mCharIndex >= ca.length) {
					mCharIndex = 0;
				}
				//Log.d("handleChar", "Index: " + mCharIndex);
				mComposing.append(ca[mCharIndex]);
				//Log.d("handleChar", "settingCompose: " + mComposing.toString());
				currentInputConnection.setComposingText(mComposing, 1);

				t9releasehandler.postDelayed(mt9release, T9DELAY);
				if (newChar) {
					// consume single caps
					if (mCapsMode == T9Preferences.CASE_CAPITALIZE) {
						mCapsMode = T9Preferences.CASE_LOWER;
					}
				}
				updateCandidates();
				updateTextCase(getCurrentInputEditorInfo());
				break;

			case T9Preferences.MODE_123:
				if (keyCode == KeyEvent.KEYCODE_POUND) {
					onText("#");
				} else if (keyCode == KeyEvent.KEYCODE_STAR) {
					onText("*");
				} else {
					onText(String.valueOf(keyCode - KeyEvent.KEYCODE_0));
				}
				break;
			default:
				Log.e("handleCharacter", "Unknown input?");
		}
	}

	// This is a really hacky way to handle DPAD long presses in a way that we can pass them on to
	// the underlying edit box in a somewhat reliable manner.
	// (somewhat because there are a few cases where this doesn't work properly or acts strangely.)
	private boolean handleDPAD(int keyCode, KeyEvent event, boolean keyDown) {
		// Log.d("handleConsumeDPAD", "keyCode: " + keyCode + " isKeyDown: " +
		// isKeyDown);
		if (keyDown) {
			// track key, if seeing repeat count < 0, start sending this event
			// and previous to super
			if (event.getRepeatCount() == 0) {
				// store event
				mDPADkeyEvent = event;
				return true;
			} else {
				if (mIgnoreDPADKeyUp) {
					// pass events to super
					return super.onKeyDown(keyCode, event);
				} else {
					// pass previous event and future events to super
					mIgnoreDPADKeyUp = true;
					currentInputConnection.sendKeyEvent(mDPADkeyEvent);
					return super.onKeyDown(keyCode, event);
				}
			}
		} else {
			// if we have been sending previous down events to super, do the
			// same for up, else process the event
			if (mIgnoreDPADKeyUp) {
				mIgnoreDPADKeyUp = false;
				return super.onKeyUp(keyCode, event);
			} else {
				if (mKeyMode != T9Preferences.MODE_123 && mComposing.length() > 0) {
					if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
						mCandidateView.scrollToSuggestion(1);
						if (mSuggestionStrings.size() > mCandidateView.mSelectedIndex)
							currentInputConnection.setComposingText(mSuggestionStrings.get(mCandidateView.mSelectedIndex), 1);
						return true;
					} else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
						mCandidateView.scrollToSuggestion(-1);
						if (mSuggestionStrings.size() > mCandidateView.mSelectedIndex)
							currentInputConnection.setComposingText(mSuggestionStrings.get(mCandidateView.mSelectedIndex), 1);
						return true;
					} else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
						if (mKeyMode == T9Preferences.MODE_PREDICTIVE) {
							commitTyped();
						} else if (mKeyMode == T9Preferences.MODE_ABC) {
							commitReset();
						}
						// getCurrentInputConnection().sendKeyEvent(mDPADkeyEvent);
						// return super.onKeyUp(keyCode, event);
						return true;
					}
				}
				if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
					handleMidButton();
					return true;
				} else {// Send stored event to input connection then pass current
					// event onto super
					currentInputConnection.sendKeyEvent(mDPADkeyEvent);
					return super.onKeyUp(keyCode, event);
				}
			}
		}
	}

	private boolean isThereText() {
		if (getCurrentInputConnection() == null) {
			return false;
		}

		ExtractedText extractedText = getCurrentInputConnection().getExtractedText(new ExtractedTextRequest(), 0);
		return extractedText != null && extractedText.text.length() > 0;
	}

	private void commitReset() {
		commitTyped();
		charReset();
		if (mCapsMode == T9Preferences.CASE_CAPITALIZE) {
			mCapsMode = T9Preferences.CASE_LOWER;
		}
		// Log.d("commitReset", "CM pre: " + mCapsMode);
		updateTextCase(getCurrentInputEditorInfo());
		// Log.d("commitReset", "CM post: " + mCapsMode);
	}

	private void charReset() {
		t9releasehandler.removeCallbacks(mt9release);
		mPrevious = -1;
		mCharIndex = 0;
	}

	private void handleClose() {
		commitTyped();
		requestHideSelf(0);
	}

	protected void nextKeyMode() {
		if (mKeyMode == MODE_CYCLE.length - 1) {
			mKeyMode = 0;
		}
		else {
			mKeyMode++;
		}
		updateStatusIcon();
		resetKeyMode();
	}

	private void nextLang() {
		mLangIndex++;
		if (mLangIndex == mLangsAvailable.length) {
			mLangIndex = 0;
		}
		mLang = mLangsAvailable[mLangIndex];
		updateStatusIcon();
	}

	private void resetKeyMode() {
		charReset();
		if (mKeyMode != T9Preferences.MODE_123) {
			commitTyped();
		}
		mComposing.setLength(0);
		mComposingI.setLength(0);
		currentInputConnection.finishComposingText();
	}

	/**
	 * Set the status icon that is appropriate in current mode (based on
	 * openwmm-legacy)
	 */
	private void updateStatusIcon() {
		int icon = 0;

		switch (mKeyMode) {
			case T9Preferences.MODE_ABC:
				interfacehandler.showHold(false);
				icon = LangHelper.ICONMAP[mLang.index][mKeyMode][mCapsMode];
				break;
			case T9Preferences.MODE_PREDICTIVE:
				if (!db.isReady()) {
					if (!mGaveUpdateWarn) {
						Toast.makeText(this, getText(R.string.updating_database_unavailable), Toast.LENGTH_LONG).show();
						mGaveUpdateWarn = true;
					}
					nextKeyMode();
					return;
				}
				if (mLangIndex == -1) {
					nextKeyMode();
					return;
				}
				if (mAddingWord) {
					interfacehandler.showHold(false);
				} else {
					interfacehandler.showHold(true);
				}
				//Log.d("T9.updateStatusIcon", "lang: " + mLang + " mKeyMode: " + mKeyMode + " mCapsMode"
				// + mCapsMode);
				icon = LangHelper.ICONMAP[mLang.index][mKeyMode][mCapsMode];
				break;
			case T9Preferences.MODE_123:
				interfacehandler.showHold(false);
				icon = R.drawable.ime_number;
				break;
			default:
				Log.e("updateStatusIcon", "How.");
				break;
		}
		showStatusIcon(icon);
	}

	private void pickSelectedCandidate(InputConnection ic) {
		pickSuggestionManually(-1, ic);
	}

	private void pickSuggestionManually(int index, InputConnection ic) {
		// Log.d("pickSuggestMan", "Doing");
		if (mComposing.length() > 0 || mComposingI.length() > 0) {
			// If we were generating candidate suggestions for the current
			// text, we would commit one of them here. But for this sample,
			// we will just commit the current text.
			if (!mSuggestionStrings.isEmpty()) {
				if (index < 0) {
					// Log.d("pickSuggestMan", "picking SELECTED: " +
					// mSuggestionStrings.get(mCandidateView.mSelectedIndex));
					// get and commit selected suggestion
					ic.commitText(mSuggestionStrings.get(mCandidateView.mSelectedIndex), 1);
					if (mKeyMode == T9Preferences.MODE_PREDICTIVE) {
						// update freq
						db.incrementWord(mSuggestionInts.get(mCandidateView.mSelectedIndex));
					}
				} else {
					// commit suggestion index
					ic.commitText(mSuggestionStrings.get(index), 1);
					if (mKeyMode == T9Preferences.MODE_PREDICTIVE) {
						db.incrementWord(mSuggestionInts.get(index));
					}
				}
			}
		}
	}

}