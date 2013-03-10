package com.coinbase.android;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.message.BasicNameValuePair;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.ListFragment;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.coinbase.android.pin.PINManager;
import com.coinbase.api.LoginManager;
import com.coinbase.api.RpcManager;

public class AccountSettingsFragment extends ListFragment implements CoinbaseFragment {

  private class RefreshSettingsTask extends AsyncTask<Void, Void, Boolean> {

    @Override
    protected Boolean doInBackground(Void... params) {

      try {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mParent);
        int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
        JSONObject userInfo = RpcManager.getInstance().callGet(mParent, "users").getJSONArray("users").getJSONObject(0).getJSONObject("user");

        Editor e = prefs.edit();

        e.putString(String.format(Constants.KEY_ACCOUNT_NAME, activeAccount), userInfo.getString("email"));
        e.putString(String.format(Constants.KEY_ACCOUNT_NATIVE_CURRENCY, activeAccount), userInfo.getString("native_currency"));
        e.putString(String.format(Constants.KEY_ACCOUNT_FULL_NAME, activeAccount), userInfo.getString("name"));
        e.putString(String.format(Constants.KEY_ACCOUNT_TIME_ZONE, activeAccount), userInfo.getString("time_zone"));
        e.putString(String.format(Constants.KEY_ACCOUNT_LIMIT, activeAccount, "buy"), userInfo.getJSONObject("buy_limit").getString("amount"));
        e.putString(String.format(Constants.KEY_ACCOUNT_LIMIT, activeAccount, "sell"), userInfo.getJSONObject("sell_limit").getString("amount"));
        e.putString(String.format(Constants.KEY_ACCOUNT_LIMIT_CURRENCY, activeAccount, "buy"), userInfo.getJSONObject("buy_limit").getString("currency"));
        e.putString(String.format(Constants.KEY_ACCOUNT_LIMIT_CURRENCY, activeAccount, "sell"), userInfo.getJSONObject("sell_limit").getString("currency"));

        e.commit(); 

        return true;
      } catch (JSONException e) {
        e.printStackTrace();
      } catch (IOException e) {
        e.printStackTrace();
      }

      return false;
    }

    @Override
    protected void onPostExecute(Boolean result) {

      setListAdapter(new PreferenceListAdapter());
    }

  }

  private class PreferenceListAdapter extends BaseAdapter {

    int mActiveAccount = -1;

    @Override
    public int getCount() {
      return mPreferences.length - (Constants.DEBUG_BUILD ? 0 : 1);
    }

    @Override
    public Object getItem(int position) {
      return mPreferences[position];
    }

    @Override
    public long getItemId(int position) {
      return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {

      SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mParent);

      if(mActiveAccount == -1) {
        mActiveAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
      }

      View view = convertView;
      Object[] item = (Object[]) getItem(position);

      if(view == null) {
        view = View.inflate(mParent, R.layout.account_item, null);
      }

      TextView text1 = (TextView) view.findViewById(android.R.id.text1),
          text2 = (TextView) view.findViewById(android.R.id.text2);

      String desc = null;
      if("limits".equals(item[2])) {

        desc = String.format(getString(R.string.account_limits_text),
            Utils.formatCurrencyAmount(prefs.getString(String.format(Constants.KEY_ACCOUNT_LIMIT, mActiveAccount, "buy"), "0")),
            prefs.getString(String.format(Constants.KEY_ACCOUNT_LIMIT_CURRENCY, mActiveAccount, "buy"), "BTC"),
            Utils.formatCurrencyAmount(prefs.getString(String.format(Constants.KEY_ACCOUNT_LIMIT, mActiveAccount, "sell"), "0")),
            prefs.getString(String.format(Constants.KEY_ACCOUNT_LIMIT_CURRENCY, mActiveAccount, "sell"), "BTC"));

      } else {
        desc = prefs.getString(
            String.format((String) item[1], mActiveAccount), null);
      }

      text1.setText((Integer) item[0]);
      text2.setText(desc);

      return view;
    }

  }

  public static class NetworkListDialogFragment extends DialogFragment {

    int selected = -1;

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {

      AlertDialog.Builder b = new AlertDialog.Builder(getActivity());

      final String[] display = getArguments().getStringArray("display");
      final String[] data = getArguments().getStringArray("data");
      final String key = getArguments().getString("key");
      final String userUpdateParam = getArguments().getString("userUpdateParam");
      selected = getArguments().getInt("selected");

      b.setSingleChoiceItems(display, selected, new DialogInterface.OnClickListener() {

        @Override
        public void onClick(DialogInterface dialog, int which) {

          selected = which;
        }
      });

      b.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {

        @Override
        public void onClick(DialogInterface dialog, int which) {

          // Update user
          ((MainActivity) getActivity()).getAccountSettingsFragment().updateUser(userUpdateParam, data[selected], key);
        }
      });

      b.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {

        @Override
        public void onClick(DialogInterface dialog, int which) {
          // Do nothing
        }
      });

      return b.create();
    }
  }

  public static class TextSettingFragment extends DialogFragment {

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {

      AlertDialog.Builder b = new AlertDialog.Builder(getActivity());

      final String key = getArguments().getString("key");
      final String userUpdateParam = getArguments().getString("userUpdateParam");

      String currentValue = PreferenceManager.getDefaultSharedPreferences(getActivity()).getString(key, null);
      DisplayMetrics metrics = getActivity().getResources().getDisplayMetrics();
      int padding = (int)(16 * metrics.density);

      final FrameLayout layout = new FrameLayout(getActivity());
      final EditText text = new EditText(getActivity());
      text.setText(currentValue);
      text.setInputType(
          InputType.TYPE_CLASS_TEXT |
          ("name".equals(key) ? InputType.TYPE_TEXT_VARIATION_PERSON_NAME : InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS));
      layout.addView(text);
      layout.setPadding(padding, padding, padding, padding);
      b.setView(layout);

      b.setTitle("Change " + userUpdateParam);

      b.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {

        @Override
        public void onClick(DialogInterface dialog, int which) {

          // Update user
          ((MainActivity) getActivity()).getAccountSettingsFragment().updateUser(userUpdateParam,
              text.getText().toString(), key);
        }
      });

      b.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {

        @Override
        public void onClick(DialogInterface dialog, int which) {
          // Do nothing
        }
      });

      return b.create();
    }
  }

  private class ShowNetworkListTask extends AsyncTask<String, Void, String[][]> {

    ProgressDialog mDialog;
    String mPreferenceKey, mUserUpdateParam;
    int mSelected = -1;

    @Override
    protected void onPreExecute() {

      mDialog = ProgressDialog.show(mParent, null, mParent.getString(R.string.account_progress));
    }

    @Override
    protected String[][] doInBackground(String... params) {

      String apiEndpoint = params[0];
      mPreferenceKey = params[1];
      mUserUpdateParam = params[2];

      String currentValue = PreferenceManager.getDefaultSharedPreferences(mParent).getString(mPreferenceKey, null);

      try {

        JSONArray array = RpcManager.getInstance().callGet(mParent, apiEndpoint).getJSONArray("response");

        String[] display = new String[array.length()];
        String[] data = new String[array.length()];

        for(int i = 0; i < array.length(); i++) {
          display[i] = array.getJSONArray(i).getString(0);
          data[i] = array.getJSONArray(i).getString(1);

          if(data[i].equalsIgnoreCase(currentValue)) {
            mSelected = i;
          }
        }

        return new String[][] { display, data };

      } catch (JSONException e) {
        e.printStackTrace();
        return null;
      } catch (IOException e) {
        e.printStackTrace();
        return null;
      }
    }

    @Override
    protected void onPostExecute(String[][] result) {

      mDialog.dismiss();

      if(result == null) {

        Toast.makeText(mParent, R.string.account_list_error, Toast.LENGTH_SHORT).show();
      } else {

        NetworkListDialogFragment f = new NetworkListDialogFragment();
        Bundle args = new Bundle();
        args.putStringArray("display", result[0]);
        args.putStringArray("data", result[1]);
        args.putString("key", mPreferenceKey);
        args.putInt("selected", mSelected);
        args.putString("userUpdateParam", mUserUpdateParam);
        f.setArguments(args);
        f.show(mParent.getSupportFragmentManager(), "networklist");
      }
    }

  }

  private class UpdateUserTask extends AsyncTask<String, Void, Boolean> {

    ProgressDialog mDialog;

    @Override
    protected void onPreExecute() {

      mDialog = ProgressDialog.show(mParent, null, mParent.getString(R.string.account_save_progress));
    }

    @Override
    protected Boolean doInBackground(String... params) {

      String userUpdateParam = params[0];
      String value = params[1];
      String prefsKey = params[2];

      try {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mParent);
        int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
        String userId = prefs.getString(String.format(Constants.KEY_ACCOUNT_ID, activeAccount), null);

        List<BasicNameValuePair> postParams = new ArrayList<BasicNameValuePair>();
        postParams.add(new BasicNameValuePair("user[" + userUpdateParam + "]", value));
        JSONObject response = RpcManager.getInstance().callPut(mParent, "users/" + userId, postParams);

        boolean success = response.optBoolean("success");

        if(success) { 
          Editor e = prefs.edit();
          e.putString(prefsKey, value);
          e.commit();
        } else {
          Log.e("Coinbase", "Got error when updating user: " + response);
        }

        return success;

      } catch (JSONException e) {
        e.printStackTrace();
        return false;
      } catch (IOException e) {
        e.printStackTrace();
        return false;
      }
    }

    @Override
    protected void onPostExecute(Boolean result) {

      mDialog.dismiss();

      if(result) {

        Toast.makeText(mParent, R.string.account_save_success, Toast.LENGTH_SHORT).show();

        mParent.refresh();
      } else {

        Toast.makeText(mParent, R.string.account_save_error, Toast.LENGTH_SHORT).show();
      }
    }

  }

  private class LoadReceiveAddressTask extends AsyncTask<Boolean, Void, String> {

    @Override
    protected String doInBackground(Boolean... params) {

      SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mParent);
      int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);

      try {

        boolean shouldGenerateNew = params[0];
        String address;

        if(shouldGenerateNew) {

          JSONObject response = RpcManager.getInstance().callPost(mParent, "account/generate_receive_address", null);

          address = response.optString("address");

        } else {

          JSONObject response = RpcManager.getInstance().callGet(mParent, "account/receive_address");

          address = response.optString("address");
        }

        if(address != null) {
          // Save balance in preferences
          Editor editor = prefs.edit();
          editor.putString(String.format(Constants.KEY_ACCOUNT_RECEIVE_ADDRESS, activeAccount), address);
          editor.commit();
        }

        return address;

      } catch (IOException e) {

        e.printStackTrace();
      } catch (JSONException e) {

        e.printStackTrace();
      }

      return null;
    }

  }

  private Object[][] mPreferences = new Object[][] {
      { R.string.account_name, Constants.KEY_ACCOUNT_FULL_NAME, "name" },
      { R.string.account_email, Constants.KEY_ACCOUNT_NAME, "email" },
      { R.string.account_time_zone, Constants.KEY_ACCOUNT_TIME_ZONE, "time_zone" },
      { R.string.account_native_currency, Constants.KEY_ACCOUNT_NATIVE_CURRENCY, "native_currency" },
      { R.string.account_limits, Constants.KEY_ACCOUNT_LIMIT, "limits" },
      { R.string.account_receive_address, Constants.KEY_ACCOUNT_RECEIVE_ADDRESS, "receive_address" },
      { R.string.account_refresh_token, Constants.KEY_ACCOUNT_REFRESH_TOKEN, "refresh_token" }
  };

  MainActivity mParent;
  SharedPreferences.OnSharedPreferenceChangeListener mChangeListener;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setListAdapter(new PreferenceListAdapter());

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mParent);
    mChangeListener = new SharedPreferences.OnSharedPreferenceChangeListener() {

      @Override
      public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
          String key) {

        // Refresh list
        setListAdapter(new PreferenceListAdapter());
      }
    };
    prefs.registerOnSharedPreferenceChangeListener(mChangeListener);
  }

  @Override
  public void onViewCreated(View view, Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    registerForContextMenu(getListView());
  }

  @Override
  public void onDestroy() {
    super.onDestroy();

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mParent);
    prefs.unregisterOnSharedPreferenceChangeListener(mChangeListener);
  }

  @Override
  public void onAttach(Activity activity) {
    super.onAttach(activity);

    mParent = (MainActivity) activity;
  }

  public void updateUser(String key, String value, String prefsKey) {
    new UpdateUserTask().execute(key, value, prefsKey);
  }

  @Override
  public void onListItemClick(ListView l, View v, int position, long id) {

    Object[] data = (Object[]) l.getItemAtPosition(position);
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mParent);
    int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);

    if(!PINManager.getInstance().checkForEditAccess(getActivity())) {
      return;
    }

    if("name".equals(data[2]) || "email".equals(data[2])) {
      // Show text prompt
      TextSettingFragment f = new TextSettingFragment();
      Bundle args = new Bundle();
      args.putString("key", String.format((String) data[1], activeAccount));
      args.putString("userUpdateParam", (String) data[2]);
      f.setArguments(args);
      f.show(getFragmentManager(), "prompt");
    } else if("time_zone".equals(data[2])) {
      // Show list of time zones
      // Not currently implemented
    } else if("native_currency".equals(data[2])) {

      // Show list of currencies
      Utils.runAsyncTaskConcurrently(new ShowNetworkListTask(), "currencies",
          String.format(Constants.KEY_ACCOUNT_NATIVE_CURRENCY, activeAccount),
          "native_currency");
    } else if("refresh_token".equals(data[2])) {

      // Refresh token
      LoginManager.getInstance().refreshAccessToken(mParent, activeAccount);
    } else if("limits".equals(data[2])) {

      // Open browser
      Intent i = new Intent(Intent.ACTION_VIEW);
      i.addCategory(Intent.CATEGORY_BROWSABLE);
      i.setData(Uri.parse("https://coinbase.com/verifications"));
      startActivity(i);
    } else if("receive_address".equals(data[2])) {

      // Copy to clipboard
      setClipboard(prefs.getString(String.format(Constants.KEY_ACCOUNT_RECEIVE_ADDRESS, activeAccount), ""));
      Toast.makeText(mParent, R.string.account_receive_address_copied, Toast.LENGTH_SHORT).show();
    }
  }

  @TargetApi(Build.VERSION_CODES.HONEYCOMB)
  private void setClipboard(String text) {

    int currentapiVersion = android.os.Build.VERSION.SDK_INT;
    if (currentapiVersion >= android.os.Build.VERSION_CODES.HONEYCOMB) {

      android.content.ClipboardManager clipboard = 
          (android.content.ClipboardManager) mParent.getSystemService(Context.CLIPBOARD_SERVICE); 
      ClipData clip = ClipData.newPlainText("Coinbase", text);
      clipboard.setPrimaryClip(clip); 
    } else {

      android.text.ClipboardManager clipboard =
          (android.text.ClipboardManager) mParent.getSystemService(Context.CLIPBOARD_SERVICE); 
      clipboard.setText(text);
    }
  }

  @Override
  public void onCreateContextMenu(ContextMenu menu, View v,
      ContextMenuInfo _menuInfo) {

    AdapterView.AdapterContextMenuInfo menuInfo = (AdapterView.AdapterContextMenuInfo) _menuInfo;
    Object[] data = (Object[]) getListView().getItemAtPosition(menuInfo.position);

    if("receive_address".equals(data[2])) {

      menu.add(Menu.NONE, R.id.account_receive_address_generate, Menu.NONE, R.string.account_receive_address_generate);
    }
  }

  @Override
  public boolean onContextItemSelected(MenuItem item) {

    if(item.getItemId() == R.id.account_receive_address_generate) {

      if(!PINManager.getInstance().checkForEditAccess(getActivity())) {
        return true;
      }

      regenerateReceiveAddress();
      return true;
    }

    return super.onContextItemSelected(item);
  }

  public void regenerateReceiveAddress() {

    new LoadReceiveAddressTask().execute(true);
  }

  public void refresh() {

    setListAdapter(new PreferenceListAdapter());

    new RefreshSettingsTask().execute();
    new LoadReceiveAddressTask().execute(false);
  }

  @Override
  public void onSwitchedTo() {
    // Not used
  }
}
