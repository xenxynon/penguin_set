/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.applications;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.settings.DropDownPreference;
import com.android.settings.R;
import com.android.settings.Utils;
import com.android.settings.applications.ApplicationsState.AppEntry;
import com.android.settings.applications.ApplicationsState.Callbacks;
import com.android.settings.deviceinfo.StorageWizardMoveConfirm;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class AppStorageSettings extends AppInfoWithHeader
        implements OnClickListener, Callbacks, DropDownPreference.Callback {
    private static final String TAG = AppStorageSettings.class.getSimpleName();

    //internal constants used in Handler
    private static final int OP_SUCCESSFUL = 1;
    private static final int OP_FAILED = 2;
    private static final int MSG_CLEAR_USER_DATA = 1;
    private static final int MSG_CLEAR_CACHE = 3;

    // invalid size value used initially and also when size retrieval through PackageManager
    // fails for whatever reason
    private static final int SIZE_INVALID = -1;

    // Result code identifiers
    public static final int REQUEST_MANAGE_SPACE = 2;

    private static final int DLG_CLEAR_DATA = DLG_BASE + 1;
    private static final int DLG_CANNOT_CLEAR_DATA = DLG_BASE + 2;

    private static final String KEY_MOVE_PREFERENCE = "app_location_setting";
    private static final String KEY_STORAGE_SETTINGS = "storage_settings";
    private static final String KEY_CACHE_SETTINGS = "cache_settings";

    private TextView mTotalSize;
    private TextView mAppSize;
    private TextView mDataSize;
    private TextView mExternalCodeSize;
    private TextView mExternalDataSize;

    // Views related to cache info
    private TextView mCacheSize;
    private Button mClearDataButton;
    private Button mClearCacheButton;

    private DropDownPreference mMoveDropDown;

    private boolean mCanClearData = true;
    private boolean mHaveSizes = false;

    private long mLastCodeSize = -1;
    private long mLastDataSize = -1;
    private long mLastExternalCodeSize = -1;
    private long mLastExternalDataSize = -1;
    private long mLastCacheSize = -1;
    private long mLastTotalSize = -1;

    private ClearCacheObserver mClearCacheObserver;
    private ClearUserDataObserver mClearDataObserver;

    // Resource strings
    private CharSequence mInvalidSizeStr;
    private CharSequence mComputingStr;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.app_storage_settings);
        setupViews();
    }

    private void setupViews() {
        mComputingStr = getActivity().getText(R.string.computing_size);
        mInvalidSizeStr = getActivity().getText(R.string.invalid_size_value);
        LayoutPreference view = (LayoutPreference) findPreference(KEY_STORAGE_SETTINGS);

        // Set default values on sizes
        mTotalSize = (TextView) view.findViewById(R.id.total_size_text);
        mAppSize = (TextView) view.findViewById(R.id.application_size_text);
        mDataSize = (TextView) view.findViewById(R.id.data_size_text);
        mExternalCodeSize = (TextView) view.findViewById(R.id.external_code_size_text);
        mExternalDataSize = (TextView) view.findViewById(R.id.external_data_size_text);

        if (Environment.isExternalStorageEmulated()) {
            ((View) mExternalCodeSize.getParent()).setVisibility(View.GONE);
            ((View) mExternalDataSize.getParent()).setVisibility(View.GONE);
        }
        mClearDataButton = (Button) view.findViewById(R.id.clear_data_button)
                .findViewById(R.id.button);

        mMoveDropDown = (DropDownPreference) findPreference(KEY_MOVE_PREFERENCE);
        mMoveDropDown.setCallback(this);

        view = (LayoutPreference) findPreference(KEY_CACHE_SETTINGS);
        // Cache section
        mCacheSize = (TextView) view.findViewById(R.id.cache_size_text);
        mClearCacheButton = (Button) view.findViewById(R.id.clear_cache_button)
                .findViewById(R.id.button);
        mClearCacheButton.setText(R.string.clear_cache_btn_text);
    }

    @Override
    public void onClick(View v) {
        if (v == mClearCacheButton) {
            // Lazy initialization of observer
            if (mClearCacheObserver == null) {
                mClearCacheObserver = new ClearCacheObserver();
            }
            mPm.deleteApplicationCacheFiles(mPackageName, mClearCacheObserver);
        } else if(v == mClearDataButton) {
            if (mAppEntry.info.manageSpaceActivityName != null) {
                if (!Utils.isMonkeyRunning()) {
                    Intent intent = new Intent(Intent.ACTION_DEFAULT);
                    intent.setClassName(mAppEntry.info.packageName,
                            mAppEntry.info.manageSpaceActivityName);
                    startActivityForResult(intent, REQUEST_MANAGE_SPACE);
                }
            } else {
                showDialogInner(DLG_CLEAR_DATA, 0);
            }
        }
    }

    @Override
    public boolean onItemSelected(int pos, Object value) {
        final Context context = getActivity();

        // If not current volume, kick off move wizard
        final VolumeInfo targetVol = (VolumeInfo) value;
        final VolumeInfo currentVol = context.getPackageManager().getApplicationCurrentVolume(
                mAppEntry.info);
        if (!Objects.equals(targetVol, currentVol)) {
            final Intent intent = new Intent(context, StorageWizardMoveConfirm.class);
            intent.putExtra(VolumeInfo.EXTRA_VOLUME_ID, targetVol.getId());
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, mAppEntry.info.packageName);
            startActivity(intent);
        }

        return true;
    }

    private String getSizeStr(long size) {
        if (size == SIZE_INVALID) {
            return mInvalidSizeStr.toString();
        }
        return Formatter.formatFileSize(getActivity(), size);
    }

    private void refreshSizeInfo() {
        if (mAppEntry.size == ApplicationsState.SIZE_INVALID
                || mAppEntry.size == ApplicationsState.SIZE_UNKNOWN) {
            mLastCodeSize = mLastDataSize = mLastCacheSize = mLastTotalSize = -1;
            if (!mHaveSizes) {
                mAppSize.setText(mComputingStr);
                mDataSize.setText(mComputingStr);
                mCacheSize.setText(mComputingStr);
                mTotalSize.setText(mComputingStr);
            }
            mClearDataButton.setEnabled(false);
            mClearCacheButton.setEnabled(false);

        } else {
            mHaveSizes = true;
            long codeSize = mAppEntry.codeSize;
            long dataSize = mAppEntry.dataSize;
            if (Environment.isExternalStorageEmulated()) {
                codeSize += mAppEntry.externalCodeSize;
                dataSize +=  mAppEntry.externalDataSize;
            } else {
                if (mLastExternalCodeSize != mAppEntry.externalCodeSize) {
                    mLastExternalCodeSize = mAppEntry.externalCodeSize;
                    mExternalCodeSize.setText(getSizeStr(mAppEntry.externalCodeSize));
                }
                if (mLastExternalDataSize !=  mAppEntry.externalDataSize) {
                    mLastExternalDataSize =  mAppEntry.externalDataSize;
                    mExternalDataSize.setText(getSizeStr( mAppEntry.externalDataSize));
                }
            }
            if (mLastCodeSize != codeSize) {
                mLastCodeSize = codeSize;
                mAppSize.setText(getSizeStr(codeSize));
            }
            if (mLastDataSize != dataSize) {
                mLastDataSize = dataSize;
                mDataSize.setText(getSizeStr(dataSize));
            }
            long cacheSize = mAppEntry.cacheSize + mAppEntry.externalCacheSize;
            if (mLastCacheSize != cacheSize) {
                mLastCacheSize = cacheSize;
                mCacheSize.setText(getSizeStr(cacheSize));
            }
            if (mLastTotalSize != mAppEntry.size) {
                mLastTotalSize = mAppEntry.size;
                mTotalSize.setText(getSizeStr(mAppEntry.size));
            }

            if ((mAppEntry.dataSize+ mAppEntry.externalDataSize) <= 0 || !mCanClearData) {
                mClearDataButton.setEnabled(false);
            } else {
                mClearDataButton.setEnabled(true);
                mClearDataButton.setOnClickListener(this);
            }
            if (cacheSize <= 0) {
                mClearCacheButton.setEnabled(false);
            } else {
                mClearCacheButton.setEnabled(true);
                mClearCacheButton.setOnClickListener(this);
            }
        }
        if (mAppControlRestricted) {
            mClearCacheButton.setEnabled(false);
            mClearDataButton.setEnabled(false);
        }
    }

    @Override
    protected boolean refreshUi() {
        retrieveAppEntry();
        refreshButtons();
        refreshSizeInfo();

        final VolumeInfo currentVol = getActivity().getPackageManager()
                .getApplicationCurrentVolume(mAppEntry.info);
        mMoveDropDown.setSelectedValue(currentVol);

        return true;
    }

    private void refreshButtons() {
        initMoveDropDown();
        initDataButtons();
    }

    private void initDataButtons() {
        // If the app doesn't have its own space management UI
        // And it's a system app that doesn't allow clearing user data or is an active admin
        // Then disable the Clear Data button.
        if (mAppEntry.info.manageSpaceActivityName == null
                && ((mAppEntry.info.flags&(ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_ALLOW_CLEAR_USER_DATA))
                        == ApplicationInfo.FLAG_SYSTEM
                        || mDpm.packageHasActiveAdmins(mPackageName))) {
            mClearDataButton.setText(R.string.clear_user_data_text);
            mClearDataButton.setEnabled(false);
            mCanClearData = false;
        } else {
            if (mAppEntry.info.manageSpaceActivityName != null) {
                mClearDataButton.setText(R.string.manage_space_text);
            } else {
                mClearDataButton.setText(R.string.clear_user_data_text);
            }
            mClearDataButton.setOnClickListener(this);
        }

        if (mAppControlRestricted) {
            mClearDataButton.setEnabled(false);
        }
    }

    private void initMoveDropDown() {
        final Context context = getActivity();
        final StorageManager storage = context.getSystemService(StorageManager.class);

        final List<VolumeInfo> candidates = context.getPackageManager()
                .getApplicationCandidateVolumes(mAppEntry.info);
        Collections.sort(candidates, VolumeInfo.getDescriptionComparator());

        mMoveDropDown.clearItems();
        for (VolumeInfo vol : candidates) {
            final String volDescrip = storage.getBestVolumeDescription(vol);
            mMoveDropDown.addItem(volDescrip, vol);
        }
        mMoveDropDown.setSelectable(!mAppControlRestricted);
    }

    /*
     * Private method to initiate clearing user data when the user clicks the clear data
     * button for a system package
     */
    private void initiateClearUserData() {
        mClearDataButton.setEnabled(false);
        // Invoke uninstall or clear user data based on sysPackage
        String packageName = mAppEntry.info.packageName;
        Log.i(TAG, "Clearing user data for package : " + packageName);
        if (mClearDataObserver == null) {
            mClearDataObserver = new ClearUserDataObserver();
        }
        ActivityManager am = (ActivityManager)
                getActivity().getSystemService(Context.ACTIVITY_SERVICE);
        boolean res = am.clearApplicationUserData(packageName, mClearDataObserver);
        if (!res) {
            // Clearing data failed for some obscure reason. Just log error for now
            Log.i(TAG, "Couldnt clear application user data for package:"+packageName);
            showDialogInner(DLG_CANNOT_CLEAR_DATA, 0);
        } else {
            mClearDataButton.setText(R.string.recompute_size);
        }
    }

    /*
     * Private method to handle clear message notification from observer when
     * the async operation from PackageManager is complete
     */
    private void processClearMsg(Message msg) {
        int result = msg.arg1;
        String packageName = mAppEntry.info.packageName;
        mClearDataButton.setText(R.string.clear_user_data_text);
        if (result == OP_SUCCESSFUL) {
            Log.i(TAG, "Cleared user data for package : "+packageName);
            mState.requestSize(mPackageName, mUserId);
        } else {
            mClearDataButton.setEnabled(true);
        }
    }

    @Override
    protected AlertDialog createDialog(int id, int errorCode) {
        switch (id) {
            case DLG_CLEAR_DATA:
                return new AlertDialog.Builder(getActivity())
                        .setTitle(getActivity().getText(R.string.clear_data_dlg_title))
                        .setMessage(getActivity().getText(R.string.clear_data_dlg_text))
                        .setPositiveButton(R.string.dlg_ok, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                // Clear user data here
                                initiateClearUserData();
                            }
                        })
                        .setNegativeButton(R.string.dlg_cancel, null)
                        .create();
            case DLG_CANNOT_CLEAR_DATA:
                return new AlertDialog.Builder(getActivity())
                        .setTitle(getActivity().getText(R.string.clear_failed_dlg_title))
                        .setMessage(getActivity().getText(R.string.clear_failed_dlg_text))
                        .setNeutralButton(R.string.dlg_ok, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                mClearDataButton.setEnabled(false);
                                //force to recompute changed value
                                setIntentAndFinish(false, false);
                            }
                        })
                        .create();
        }
        return null;
    }

    @Override
    public void onPackageSizeChanged(String packageName) {
        if (packageName.equals(mAppEntry.info.packageName)) {
            refreshSizeInfo();
        }
    }

    private final Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            if (getView() == null) {
                return;
            }
            switch (msg.what) {
                case MSG_CLEAR_USER_DATA:
                    processClearMsg(msg);
                    break;
                case MSG_CLEAR_CACHE:
                    // Refresh size info
                    mState.requestSize(mPackageName, mUserId);
                    break;
            }
        }
    };

    public static CharSequence getSummary(AppEntry appEntry, Context context) {
        if (appEntry.size == ApplicationsState.SIZE_INVALID
                || appEntry.size == ApplicationsState.SIZE_UNKNOWN) {
            return context.getText(R.string.computing_size);
        } else {
            CharSequence storageType = context.getString(
                    (appEntry.info.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0
                    ? R.string.storage_type_external
                    : R.string.storage_type_internal);
            return context.getString(R.string.storage_summary_format,
                    getSize(appEntry, context), storageType);
        }
    }

    private static CharSequence getSize(AppEntry appEntry, Context context) {
        long size = appEntry.size;
        if (size == SIZE_INVALID) {
            return context.getText(R.string.invalid_size_value);
        }
        return Formatter.formatFileSize(context, size);
    }

    @Override
    protected int getMetricsCategory() {
        return MetricsLogger.APPLICATIONS_APP_STORAGE;
    }

    class ClearCacheObserver extends IPackageDataObserver.Stub {
        public void onRemoveCompleted(final String packageName, final boolean succeeded) {
            final Message msg = mHandler.obtainMessage(MSG_CLEAR_CACHE);
            msg.arg1 = succeeded ? OP_SUCCESSFUL : OP_FAILED;
            mHandler.sendMessage(msg);
        }
    }

    class ClearUserDataObserver extends IPackageDataObserver.Stub {
       public void onRemoveCompleted(final String packageName, final boolean succeeded) {
           final Message msg = mHandler.obtainMessage(MSG_CLEAR_USER_DATA);
           msg.arg1 = succeeded ? OP_SUCCESSFUL : OP_FAILED;
           mHandler.sendMessage(msg);
        }
    }
}
