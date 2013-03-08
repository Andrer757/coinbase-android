package com.coinbase.android;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Hashtable;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.FragmentManager;
import android.support.v4.widget.SimpleCursorAdapter;
import android.widget.FilterQueryProvider;

import com.coinbase.android.db.TransactionsDatabase;
import com.coinbase.android.db.TransactionsDatabase.EmailEntry;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

public class Utils {

  private Utils() { }

  public static final void showMessageDialog(FragmentManager m, String message) {

    MessageDialogFragment fragment = new MessageDialogFragment();
    Bundle args = new Bundle();
    args.putString(MessageDialogFragment.ARG_MESSAGE, message);
    fragment.setArguments(args);
    fragment.show(m, "Utils.showMessageDialog");
  }

  public static enum CurrencyType {
    BTC(4, 1),
    TRADITIONAL(2, 2);


    int maximumFractionDigits;
    int minimumFractionDigits;

    CurrencyType(int max, int min) {
      maximumFractionDigits = max;
      minimumFractionDigits = min;
    }
  }

  public static final String formatCurrencyAmount(String amount) {
    return formatCurrencyAmount(new BigDecimal(amount), false, CurrencyType.BTC);
  }

  public static final String formatCurrencyAmount(BigDecimal amount) {
    return formatCurrencyAmount(amount, false, CurrencyType.BTC);
  }

  public static final String formatCurrencyAmount(String amount, boolean ignoreSign) {
    return formatCurrencyAmount(new BigDecimal(amount), false, CurrencyType.BTC);
  }

  public static final String formatCurrencyAmount(BigDecimal balanceNumber, boolean ignoreSign, CurrencyType type) {

    Locale locale = Locale.getDefault();
    NumberFormat nf = NumberFormat.getInstance(locale);
    nf.setMaximumFractionDigits(type.maximumFractionDigits);
    nf.setMinimumFractionDigits(type.minimumFractionDigits);

    if(ignoreSign && balanceNumber.compareTo(BigDecimal.ZERO) == -1) {
      balanceNumber = balanceNumber.multiply(new BigDecimal(-1));
    }

    return nf.format(balanceNumber);
  }

  /** Based off of ZXing Android client code */
  public static Bitmap createBarcode(String contents, BarcodeFormat format,
      int desiredWidth, int desiredHeight) throws WriterException {

    Hashtable<EncodeHintType,Object> hints = new Hashtable<EncodeHintType,Object>(2);
    MultiFormatWriter writer = new MultiFormatWriter();    
    BitMatrix result = writer.encode(contents, format, desiredWidth, desiredHeight, hints);

    int width = result.getWidth();
    int height = result.getHeight();
    int fgColor = 0xFF000000;
    int bgColor = 0x00FFFFFF;
    int[] pixels = new int[width * height];

    for (int y = 0; y < height; y++) {
      int offset = y * width;
      for (int x = 0; x < width; x++) {
        pixels[offset + x] = result.get(x, y) ? fgColor : bgColor;
      }
    }

    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
    return bitmap;
  }

  private static class EmailAutocompleteAdapter extends SimpleCursorAdapter {

    public EmailAutocompleteAdapter(Context context, int layout, Cursor c,
        String[] from, int[] to, int flags) {
      super(context, layout, c, from, to, flags);
    }

    private SQLiteDatabase mDb;
  }

  /** Important note: a call to disposeOfEmailAutocompleteAdapter must be made when you are done with the Adapter */
  public static SimpleCursorAdapter getEmailAutocompleteAdapter(final Context context) {

    String[] from = { EmailEntry.COLUMN_NAME_EMAIL };
    int[] to = { android.R.id.text1 };
    final EmailAutocompleteAdapter adapter = new EmailAutocompleteAdapter(context, android.R.layout.simple_spinner_dropdown_item, null,
        from, to, 0);

    adapter.setCursorToStringConverter(new SimpleCursorAdapter.CursorToStringConverter() {
      @Override
      public CharSequence convertToString(Cursor cursor) {
        int colIndex = cursor.getColumnIndexOrThrow(EmailEntry.COLUMN_NAME_EMAIL);
        return cursor.getString(colIndex);
      }
    });

    TransactionsDatabase database = new TransactionsDatabase(context);
    adapter.mDb = database.getReadableDatabase();

    adapter.setFilterQueryProvider(new FilterQueryProvider() {
      @Override
      public Cursor runQuery(CharSequence description) {

        if(description == null) {
          description = "";
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);

        Cursor c = adapter.mDb.query(EmailEntry.TABLE_NAME,
            null, EmailEntry.COLUMN_NAME_ACCOUNT + " = ? AND " + EmailEntry.COLUMN_NAME_EMAIL + " LIKE ?",
            new String[] { Integer.toString(activeAccount), "%" + description + "%" }, null, null, null);

        return c;
      }
    });

    return adapter;
  }

  public static void disposeOfEmailAutocompleteAdapter(SimpleCursorAdapter autocompleteAdapter) {

    if(autocompleteAdapter instanceof EmailAutocompleteAdapter) {
      ((EmailAutocompleteAdapter) autocompleteAdapter).mDb.close();
    }
  }

  public static String generateTransactionSummary(Context c, JSONObject t) throws JSONException {

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(c);
    int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);

    String currentUserId = prefs.getString(String.format(Constants.KEY_ACCOUNT_ID, activeAccount), null);

    if(currentUserId != null &&
        t.optJSONObject("sender") != null &&
        currentUserId.equals(t.getJSONObject("sender").optString("id"))) {

      JSONObject r = t.optJSONObject("recipient");
      String recipientName = null;

      if(r == null) { 
        recipientName = c.getString(R.string.transaction_user_external);
      } else { 

        if("transfers@coinbase.com".equals(r.optString("email"))) {
          // This was a bitcoin sell
          return c.getString(R.string.transaction_summary_sell);
        }

        recipientName = r.optString("name", 
            r.optString("email", c.getString(R.string.transaction_user_external)));
      }

      if(t.getBoolean("request")) {
        return String.format(c.getString(R.string.transaction_summary_request_me), recipientName);
      } else {
        return String.format(c.getString(R.string.transaction_summary_send_me), recipientName);
      }
    } else {

      JSONObject r = t.optJSONObject("sender");
      String senderName = null;

      if(r == null) { 
        senderName = c.getString(R.string.transaction_user_external);
      } else { 

        if("transfers@coinbase.com".equals(r.optString("email"))) {
          // This was a bitcoin buy
          return c.getString(R.string.transaction_summary_buy);
        }

        senderName = r.optString("name", 
            r.optString("email", c.getString(R.string.transaction_user_external)));
      }

      if(t.getBoolean("request")) {
        return String.format(c.getString(R.string.transaction_summary_request_them), senderName);
      } else {
        return String.format(c.getString(R.string.transaction_summary_send_them), senderName);
      }
    }
  }

  public static String getErrorStringFromJson(JSONObject response, String delimiter) throws JSONException {


    JSONArray errors = response.getJSONArray("errors");
    String errorMessage = "";

    for(int i = 0; i < errors.length(); i++) {
      errorMessage += (errorMessage.equals("") ? "" : delimiter) + errors.getString(i);
    }
    return errorMessage;
  }

  @TargetApi(Build.VERSION_CODES.HONEYCOMB)
  public static <T> void runAsyncTaskConcurrently(AsyncTask<T, ?, ?> task, T... params) {

    if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.HONEYCOMB) {
      task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, params);
    } else {
      task.execute(params);
    }
  }
}
