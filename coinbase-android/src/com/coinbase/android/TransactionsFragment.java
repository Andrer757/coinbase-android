package com.coinbase.android;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.http.message.BasicNameValuePair;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import android.annotation.TargetApi;
import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.ListFragment;
import android.support.v4.widget.CursorAdapter;
import android.support.v4.widget.SimpleCursorAdapter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AbsListView.OnScrollListener;
import android.widget.FrameLayout;
import android.widget.HeaderViewListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.coinbase.android.Utils.CurrencyType;
import com.coinbase.android.db.TransactionsDatabase;
import com.coinbase.android.db.TransactionsDatabase.TransactionEntry;
import com.coinbase.api.LoginManager;
import com.coinbase.api.RpcManager;

public class TransactionsFragment extends ListFragment {

  private class LoadBalanceTask extends AsyncTask<Void, Void, String[]> {

    @Override
    protected String[] doInBackground(Void... params) {

      try {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mParent);
        int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);

        JSONObject balance = RpcManager.getInstance().callGet(mParent, "account/balance");
        JSONObject exchangeRates = RpcManager.getInstance().callGet(mParent, "currencies/exchange_rates");

        String userHomeCurrency = prefs.getString(String.format(Constants.KEY_ACCOUNT_NATIVE_CURRENCY, activeAccount),
            "usd").toLowerCase(Locale.CANADA);
        BigDecimal homeAmount = new BigDecimal(balance.getString("amount")).multiply(
            new BigDecimal(exchangeRates.getString("btc_to_" + userHomeCurrency)));

        String balanceString = Utils.formatCurrencyAmount(balance.getString("amount"));
        String balanceHomeString = Utils.formatCurrencyAmount(homeAmount, false, CurrencyType.TRADITIONAL);

        String[] result = new String[] { balanceString, balance.getString("currency"),
            balanceHomeString, userHomeCurrency.toUpperCase(Locale.CANADA) };

        // Save balance in preferences
        Editor editor = prefs.edit();
        editor.putString(String.format(Constants.KEY_ACCOUNT_BALANCE, activeAccount), result[0]);
        editor.putString(String.format(Constants.KEY_ACCOUNT_BALANCE_CURRENCY, activeAccount), result[1]);
        editor.putString(String.format(Constants.KEY_ACCOUNT_BALANCE_HOME, activeAccount), result[2]);
        editor.putString(String.format(Constants.KEY_ACCOUNT_BALANCE_HOME_CURRENCY, activeAccount), result[3]);
        editor.commit();

        return result;

      } catch (IOException e) {

        e.printStackTrace();
      } catch (JSONException e) {

        e.printStackTrace();
      }

      return null;
    }

    @Override
    protected void onPreExecute() {

      mBalanceLoading = true;

      if(mBalanceText != null) {
        mBalanceText.setTextColor(mParent.getResources().getColor(R.color.wallet_balance_color_invalid));
      }
    }

    @Override
    protected void onPostExecute(String[] result) {

      mBalanceLoading = false;

      if(mBalanceText == null) {
        return;
      }

      if(result == null) {
        mBalanceText.setTextColor(mParent.getResources().getColor(R.color.wallet_balance_color_invalid));
      } else {

        mBalanceText.setTextColor(mParent.getResources().getColor(R.color.wallet_balance_color));
        mBalanceText.setText(String.format(mParent.getString(R.string.wallet_balance), result[0]));
        mBalanceCurrency.setText(String.format(mParent.getString(R.string.wallet_balance_currency), result[1]));
        mBalanceHome.setText(String.format(mParent.getString(R.string.wallet_balance_home), result[2], result[3]));
      }
    }

    @Override
    protected void onCancelled(String[] result) {

      mBalanceLoading = false;
    }

  }

  private class SyncTransactionsTask extends AsyncTask<Integer, Void, Boolean> {

    public static final int MAX_PAGES = 3;
    public static final int MAX_ENDLESS_PAGES = 10;

    /**
     * Number of pages of transactions to sync extra transfer-related data.
     */
    public static final int MAX_TRANSFER_SYNC_PAGES = 3;

    @Override
    protected Boolean doInBackground(Integer... params) {

      List<JSONObject> transactions = new ArrayList<JSONObject>();
      Map<String, JSONObject> transfers = null;
      String currentUserId = null;
      SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mParent);
      int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);

      int startPage = (params.length == 0 || params[0] == null) ? 0 : params[0];
      int loadedPage;

      // Make API call to download list of transactions
      try {

        int numPages = 1; // Real value will be set after first list iteration
        loadedPage = startPage;

        // Loop is required to sync all pages of transaction history
        for(int i = startPage + 1; i <= startPage + Math.min(numPages, MAX_PAGES); i++) {

          List<BasicNameValuePair> getParams = new ArrayList<BasicNameValuePair>();
          getParams.add(new BasicNameValuePair("page", Integer.toString(i)));
          JSONObject response = RpcManager.getInstance().callGet(mParent, "transactions", getParams);

          currentUserId = response.getJSONObject("current_user").getString("id");

          JSONArray transactionsArray = response.optJSONArray("transactions");

          if(transactionsArray == null) {
            // No transactions
            continue;
          }
          numPages = response.getInt("num_pages");

          for(int j = 0; j < transactionsArray.length(); j++) {

            JSONObject transaction = transactionsArray.getJSONObject(j).getJSONObject("transaction");
            transactions.add(transaction);
          }

          loadedPage++;
        }

        if(startPage == 0) {
          transfers = fetchTransferData();
        }

        mMaxPage = numPages;

      } catch (IOException e) {
        Log.e("Coinbase", "I/O error refreshing transactions.");
        e.printStackTrace();

        return false;
      } catch (JSONException e) {
        // Malformed response from Coinbase.
        Log.e("Coinbase", "Could not parse JSON response from Coinbase, aborting refresh of transactions.");
        e.printStackTrace();

        return false;
      }

      TransactionsDatabase dbHelper = new TransactionsDatabase(mParent);
      SQLiteDatabase db = dbHelper.getWritableDatabase();

      db.beginTransaction();
      try {


        if(startPage == 0) {
          // Remove all old transactions
          db.delete(TransactionEntry.TABLE_NAME, TransactionEntry.COLUMN_NAME_ACCOUNT + " = ?", new String[] { Integer.toString(activeAccount) });
        }

        // Update user ID
        Editor editor = prefs.edit();
        editor.putString(String.format(Constants.KEY_ACCOUNT_ID, activeAccount), currentUserId);
        editor.commit();

        for(JSONObject transaction : transactions) {

          ContentValues values = new ContentValues();

          String createdAtStr = transaction.optString("created_at", null);
          long createdAt;
          try {
            if(createdAtStr != null) {
              createdAt = ISO8601.toCalendar(createdAtStr).getTimeInMillis();
            } else {
              createdAt = -1;
            }
          } catch (ParseException e) {
            // Error parsing createdAt
            e.printStackTrace();
            createdAt = -1;
          }

          JSONObject transferData = null;

          if(transfers != null) {

            String id = transaction.optString("id");
            transferData = transfers.get(id);
          }

          values.put(TransactionEntry._ID, transaction.getString("id"));
          values.put(TransactionEntry.COLUMN_NAME_JSON, transaction.toString());
          values.put(TransactionEntry.COLUMN_NAME_TIME, createdAt);
          values.put(TransactionEntry.COLUMN_NAME_ACCOUNT, activeAccount);
          values.put(TransactionEntry.COLUMN_NAME_TRANSFER_JSON, transferData == null ? null : transferData.toString());
          values.put(TransactionEntry.COLUMN_NAME_IS_TRANSFER, transferData == null ? 0 : 1);

          db.insert(TransactionEntry.TABLE_NAME, null, values);
        }

        db.setTransactionSuccessful();
        mLastLoadedPage = loadedPage;

        // Update list
        loadTransactionsList();

        // Update transaction widgets
        updateWidgets();

        // Update the buy / sell history list
        mParent.getBuySellFragment().onTransactionsSynced();

        return true;

      } catch (JSONException e) {
        // Malformed response from Coinbase.
        Log.e("Coinbase", "Could not parse JSON response from Coinbase, aborting refresh of transactions.");
        e.printStackTrace();

        return false;
      } finally {

        db.endTransaction();
        db.close();
      }
    }

    private Map<String, JSONObject> fetchTransferData() {

      try {

        Map<String, JSONObject> transfers = new HashMap<String, JSONObject>();

        int numTransferPages = 1;
        for(int i = 1; i <= Math.min(numTransferPages, MAX_TRANSFER_SYNC_PAGES); i++) {

          List<BasicNameValuePair> getParams = new ArrayList<BasicNameValuePair>();
          getParams.add(new BasicNameValuePair("page", Integer.toString(i)));
          JSONObject response = RpcManager.getInstance().callGet(mParent, "transfers", getParams);
          JSONArray transfersArray = response.optJSONArray("transfers");

          if(transfersArray == null || transfersArray.length() == 0) {
            return null; // No transfers
          }

          numTransferPages = response.getInt("num_pages");

          for(int j = 0; j < transfersArray.length(); j++) {

            JSONObject transfer = transfersArray.getJSONObject(j).getJSONObject("transfer");
            transfers.put(transfer.optString("transaction_id"), transfer);
          }
        }

        return transfers;

      } catch (IOException e) {
        e.printStackTrace();
      } catch (JSONException e) {
        e.printStackTrace();
      }

      Log.e("Coinbase", "Could not fetch transfer data");
      return null;
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private void updateWidgets() {
      if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
        AppWidgetManager widgetManager = AppWidgetManager.getInstance(mParent);
        widgetManager.notifyAppWidgetViewDataChanged(
            widgetManager.getAppWidgetIds(new ComponentName(mParent, TransactionsAppWidgetProvider.class)),
            R.id.widget_list);
      }
    }

    @Override
    protected void onPreExecute() {

      ((MainActivity) mParent).setRefreshButtonAnimated(true);

      if(mSyncErrorView != null) {
        mSyncErrorView.setVisibility(View.GONE);
      }
    }

    @Override
    protected void onPostExecute(Boolean result) {

      ((MainActivity) mParent).setRefreshButtonAnimated(false);

      if(result != null && !result && mSyncErrorView != null) {
        mSyncErrorView.setVisibility(View.VISIBLE);
      }

      mSyncTask = null;
    }

  }

  private class TransactionViewBinder implements SimpleCursorAdapter.ViewBinder {

    @Override
    public boolean setViewValue(View arg0, Cursor arg1, int arg2) {

      try {
        JSONObject item = new JSONObject(new JSONTokener(arg1.getString(arg2)));

        switch(arg0.getId()) {

        case R.id.transaction_title:

          ((TextView) arg0).setText(Utils.generateTransactionSummary(mParent, item));
          return true;

        case R.id.transaction_amount: 

          String amount = item.getJSONObject("amount").getString("amount");
          String balanceString = Utils.formatCurrencyAmount(amount);

          int sign = new BigDecimal(amount).compareTo(BigDecimal.ZERO);
          int color = sign == -1 ? R.color.transaction_negative : (sign == 0 ? R.color.transaction_neutral : R.color.transaction_positive);

          ((TextView) arg0).setText(balanceString);
          ((TextView) arg0).setTextColor(getResources().getColor(color));
          return true;

        case R.id.transaction_currency: 

          ((TextView) arg0).setText(item.getJSONObject("amount").getString("currency"));
          return true;

        case R.id.transaction_status: 

          String status = item.optString("status", getString(R.string.transaction_status_error));

          String readable = status;
          int background = R.drawable.transaction_unknown;
          if("complete".equals(status)) {
            readable = getString(R.string.transaction_status_complete);
            background = R.drawable.transaction_complete;
          } else if("pending".equals(status)) {
            readable = getString(R.string.transaction_status_pending);
            background = R.drawable.transaction_pending;
          }

          ((TextView) arg0).setText(readable);
          ((TextView) arg0).setBackgroundResource(background);
          return true;
        }

        return false;
      } catch (JSONException e) {
        // Malformed transaction JSON.
        Log.e("Coinbase", "Corrupted database entry! " + arg1.getInt(arg1.getColumnIndex(TransactionEntry._ID)));
        e.printStackTrace();

        return true;
      }
    }
  }

  private class LoadTransactionsTask extends AsyncTask<Void, Void, Cursor> {

    @Override
    protected Cursor doInBackground(Void... params) {

      TransactionsDatabase database = new TransactionsDatabase(mParent);
      SQLiteDatabase readableDb = database.getReadableDatabase();

      SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mParent);
      int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);

      Cursor c = readableDb.query(TransactionsDatabase.TransactionEntry.TABLE_NAME,
          null, TransactionEntry.COLUMN_NAME_ACCOUNT + " = ?", new String[] { Integer.toString(activeAccount) }, null, null, null);
      return c;
    }

    @Override
    protected void onPostExecute(Cursor result) {

      if(mListView != null) {

        setHeaderPinned(!result.moveToFirst());

        if(mListView.getAdapter() != null) {

          // Just update existing adapter
          getAdapter().changeCursor(result);
          return;
        }

        String[] from = { TransactionEntry.COLUMN_NAME_JSON, TransactionEntry.COLUMN_NAME_JSON,
            TransactionEntry.COLUMN_NAME_JSON, TransactionEntry.COLUMN_NAME_JSON };
        int[] to = { R.id.transaction_title, R.id.transaction_amount,
            R.id.transaction_status, R.id.transaction_currency };
        SimpleCursorAdapter adapter = new SimpleCursorAdapter(mParent, R.layout.fragment_transactions_item, result,
            from, to, 0);
        adapter.setViewBinder(new TransactionViewBinder());
        mListView.setAdapter(adapter);
      }
    }
  }

  private class TransactionsInfiniteScrollListener implements OnScrollListener {

    @Override
    public void onScroll(AbsListView view, int firstVisibleItem,
        int visibleItemCount, int totalItemCount) {

      int padding = 2;
      boolean shouldLoadMore = firstVisibleItem + visibleItemCount + padding >= totalItemCount;

      if(shouldLoadMore && mLastLoadedPage != -1 && mLastLoadedPage < SyncTransactionsTask.MAX_ENDLESS_PAGES &&
          mLastLoadedPage != mMaxPage) {

        // Load more transactions
        if(mSyncTask == null) {
          mSyncTask = new SyncTransactionsTask();
          mSyncTask.execute(mLastLoadedPage);
        }
      }
    }

    @Override
    public void onScrollStateChanged(AbsListView view, int scrollState) {
      // Unused
    }
  }

  MainActivity mParent;
  boolean mBalanceLoading;
  FrameLayout mListHeaderContainer;
  ListView mListView;
  ViewGroup mListHeader, mMainView;
  TextView mBalanceText, mBalanceCurrency, mBalanceHome, mAccount;
  TextView mSyncErrorView;

  SyncTransactionsTask mSyncTask;
  int mLastLoadedPage = -1, mMaxPage = -1;

  @Override
  public void onCreate(Bundle savedInstanceState) {

    super.onCreate(savedInstanceState);
  }

  @Override
  public void onAttach(Activity activity) {

    super.onAttach(activity);
    mParent = (MainActivity) activity;
  }

  @Override
  public void onSaveInstanceState(Bundle outState) {

    super.onSaveInstanceState(outState);

    if(mBalanceText != null) {
      outState.putString("balance_text", mBalanceText.getText().toString());
      outState.putString("balance_currency", mBalanceCurrency.getText().toString());
    }
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container,
      Bundle savedInstanceState) {

    // Inflate base layout
    ViewGroup view = (ViewGroup) inflater.inflate(R.layout.fragment_transactions, container, false);
    mMainView = view;

    mListView = (ListView) view.findViewById(android.R.id.list);

    // Inflate header (which contains account balance)
    mListHeader = (ViewGroup) inflater.inflate(R.layout.fragment_transactions_header, null, false);
    mListHeaderContainer = new FrameLayout(mParent);
    setHeaderPinned(true);
    mListView.addHeaderView(mListHeaderContainer);

    mListView.setOnScrollListener(new TransactionsInfiniteScrollListener());

    mBalanceText = (TextView) mListHeader.findViewById(R.id.wallet_balance);
    mBalanceCurrency = (TextView) mListHeader.findViewById(R.id.wallet_balance_currency);
    mBalanceHome = (TextView) mListHeader.findViewById(R.id.wallet_balance_home);
    mAccount = (TextView) mListHeader.findViewById(R.id.wallet_account);
    mSyncErrorView = (TextView) mListHeader.findViewById(R.id.wallet_error);

    mAccount.setText(LoginManager.getInstance().getSelectedAccountName(mParent));

    // Load old balance
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mParent);
    int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
    String oldBalance = prefs.getString(String.format(Constants.KEY_ACCOUNT_BALANCE, activeAccount), null);
    String oldCurrency = prefs.getString(String.format(Constants.KEY_ACCOUNT_BALANCE_CURRENCY, activeAccount), null);
    String oldHomeBalance = prefs.getString(String.format(Constants.KEY_ACCOUNT_BALANCE_HOME, activeAccount), null);
    String oldHomeCurrency = prefs.getString(String.format(Constants.KEY_ACCOUNT_BALANCE_HOME_CURRENCY, activeAccount), null);

    if(oldBalance != null) {
      mBalanceText.setText(oldBalance);
      mBalanceCurrency.setText(oldCurrency);
      mBalanceText.setTextColor(mParent.getResources().getColor(R.color.wallet_balance_color));
      mBalanceHome.setText(String.format(mParent.getString(R.string.wallet_balance_home), oldHomeBalance, oldHomeCurrency));
    }

    if(mBalanceLoading) {

      mBalanceText.setTextColor(mParent.getResources().getColor(R.color.wallet_balance_color_invalid));
    }

    view.findViewById(R.id.wallet_send).setOnClickListener(new View.OnClickListener() {

      @Override
      public void onClick(View v) {

        mParent.openTransferMenu(false);
      }
    });

    view.findViewById(R.id.wallet_request).setOnClickListener(new View.OnClickListener() {

      @Override
      public void onClick(View v) {

        mParent.openTransferMenu(true);
      }
    });

    // Load transaction list
    loadTransactionsList();

    return view;
  }

  public void refresh() {

    // Reload balance
    new LoadBalanceTask().execute();

    // Reload transactions
    if(mSyncTask == null) {
      mSyncTask = new SyncTransactionsTask();
      mSyncTask.execute();
    }
  }

  private void setHeaderPinned(boolean pinned) {

    mMainView.removeView(mListHeader);
    mListHeaderContainer.removeAllViews();

    if(pinned) {
      mMainView.addView(mListHeader, 0);
    } else {
      mListHeaderContainer.addView(mListHeader);
    }
  }

  @TargetApi(Build.VERSION_CODES.HONEYCOMB)
  private void loadTransactionsList() {
    if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.HONEYCOMB) {
      new LoadTransactionsTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    } else {
      new LoadTransactionsTask().execute();
    }
  }

  @Override
  public void onResume() {

    super.onResume();
  }

  private CursorAdapter getAdapter() {
    return ((CursorAdapter) ((HeaderViewListAdapter) mListView.getAdapter()).getWrappedAdapter());
  }

  @Override
  public void onListItemClick(ListView l, View v, int position, long id) {

    if(position == 0) {
      return; // Header view
    }

    position--;

    Cursor c = getAdapter().getCursor();
    c.moveToPosition(position);

    String transactionId = c.getString(c.getColumnIndex(TransactionEntry._ID));
    Intent intent = new Intent(mParent, TransactionDetailsActivity.class);
    intent.putExtra(TransactionDetailsFragment.EXTRA_ID, transactionId);
    mParent.startActivityForResult(intent, 1);
  }

}
