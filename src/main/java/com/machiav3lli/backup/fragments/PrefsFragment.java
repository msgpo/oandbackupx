package com.machiav3lli.backup.fragments;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.machiav3lli.backup.Constants;
import com.machiav3lli.backup.R;
import com.machiav3lli.backup.activities.MainActivityX;
import com.machiav3lli.backup.activities.PrefsActivity;
import com.machiav3lli.backup.handler.HandleMessages;
import com.machiav3lli.backup.handler.LanguageHelper;
import com.machiav3lli.backup.handler.NotificationHelper;
import com.machiav3lli.backup.handler.ShellCommands;
import com.machiav3lli.backup.handler.Utils;
import com.machiav3lli.backup.items.AppInfo;

import java.io.File;
import java.util.ArrayList;

import static androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode;
import static com.machiav3lli.backup.handler.FileCreationHelper.getDefaultBackupDirPath;
import static com.machiav3lli.backup.handler.FileCreationHelper.setDefaultBackupDirPath;


public class PrefsFragment extends PreferenceFragmentCompat {

    private static final int DEFAULT_DIR_CODE = 0;
    final static int RESULT_OK = 0;
    final static String TAG = Constants.classTag(".PrefsFragment");
    ArrayList<AppInfo> appInfoList = MainActivityX.originalList;
    ShellCommands shellCommands;
    HandleMessages handleMessages;
    File backupDir;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);

        findPreference(Constants.PREFS_THEME).setOnPreferenceChangeListener((preference, newValue) -> {
            Utils.setPrefsString(requireContext(), Constants.PREFS_THEME, newValue.toString());
            switch (newValue.toString()) {
                case "light":
                    setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                    break;
                case "dark":
                    setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                    break;
                default:
                    setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            }
            return true;
        });

        findPreference(Constants.PREFS_LANGUAGES).setOnPreferenceChangeListener((preference, newValue) -> {
            if (LanguageHelper.changeLanguage(requireContext(), Utils.getPrefsString(
                    requireContext(), Constants.PREFS_LANGUAGES, Constants.PREFS_LANGUAGES_DEFAULT)))
                Utils.reloadWithParentStack(requireActivity());
            return true;
        });

        Preference pref = findPreference(Constants.PREFS_PATH_BACKUP_DIRECTORY);
        assert pref != null;
        pref.setSummary(getDefaultBackupDirPath(requireContext()));
        pref.setOnPreferenceClickListener(preference -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(intent, DEFAULT_DIR_CODE);
            return true;
        });

        ArrayList<String> users = requireActivity().getIntent().getStringArrayListExtra("com.machiav3lli.backup.users");
        shellCommands = new ShellCommands(requireContext(), PreferenceManager.getDefaultSharedPreferences(requireContext()), users, requireContext().getFilesDir());
        findPreference(Constants.PREFS_QUICK_REBOOT).setOnPreferenceClickListener(preference -> {
            new AlertDialog.Builder(requireActivity())
                    .setTitle(R.string.quickRebootTitle)
                    .setMessage(R.string.quickRebootMessage)
                    .setPositiveButton(R.string.dialogYes, (dialog, which) -> shellCommands.quickReboot())
                    .setNegativeButton(R.string.dialogNo, null)
                    .show();
            return true;
        });

        Bundle extra = requireActivity().getIntent().getExtras();
        if (extra != null) backupDir = (File) extra.get("com.machiav3lli.backup.backupDir");
        findPreference(Constants.PREFS_BATCH_DELETE).setOnPreferenceClickListener(preference -> {
            final ArrayList<AppInfo> deleteList = new ArrayList<>();
            StringBuilder message = new StringBuilder();
            for (AppInfo appInfo : appInfoList) {
                if (!appInfo.isInstalled()) {
                    deleteList.add(appInfo);
                    message.append(appInfo.getLabel()).append("\n");
                }
            }
            if (!deleteList.isEmpty()) {
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.prefs_batchDelete)
                        .setMessage(message.toString().trim())
                        .setPositiveButton(R.string.dialogYes, (dialog, which) -> {
                            changesMade();
                            new Thread(() -> deleteBackups(deleteList)).start();
                        })
                        .setNegativeButton(R.string.dialogNo, null)
                        .show();
            } else {
                Toast.makeText(requireActivity(), getString(R.string.batchDeleteNothingToDelete), Toast.LENGTH_LONG).show();
            }
            return true;
        });

        findPreference(Constants.PREFS_LOGVIEWER).setOnPreferenceClickListener(preference -> {
            requireActivity().getSupportFragmentManager().beginTransaction().add(R.id.prefs_fragement, new LogsFragment()).commit();
            return true;
        });

        findPreference(Constants.PREFS_HELP).setOnPreferenceClickListener(preference -> {
            requireActivity().getSupportFragmentManager().beginTransaction().add(R.id.prefs_fragement, new HelpFragment()).commit();
            return true;
        });
    }

    private void setDefaultDir(Context context, String dir) {
        Utils.setPrefsString(requireContext(), Constants.PREFS_PATH_BACKUP_DIRECTORY, dir);
        setDefaultBackupDirPath(context, dir);
        findPreference(Constants.PREFS_PATH_BACKUP_DIRECTORY).setSummary(dir);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == DEFAULT_DIR_CODE && data != null) {
            Uri uri = data.getData();
            if (resultCode == Activity.RESULT_OK && uri != null) {
                String oldDir = getDefaultBackupDirPath(requireContext());
                String newPath = uri.getLastPathSegment().replace("primary:", "/");
                String newDir = Environment.getExternalStorageDirectory() + newPath;
                if (!oldDir.equals(newDir)) {
                    Log.i(TAG, "setting uri " + newDir);
                    setDefaultDir(requireContext(), newDir);
                }
            }
        }
    }

    public void changesMade() {
        Intent result = new Intent();
        result.putExtra("changesMade", true);
        requireActivity().setResult(RESULT_OK, result);
    }

    public void deleteBackups(ArrayList<AppInfo> deleteList) {
        handleMessages.showMessage(getString(R.string.batchDeleteMessage), "");
        for (AppInfo appInfo : deleteList) {
            if (backupDir != null) {
                handleMessages.changeMessage(getString(R.string.batchDeleteMessage), appInfo.getLabel());
                Log.i(TAG, "deleting backup of " + appInfo.getLabel());
                File backupSubDir = new File(backupDir, appInfo.getPackageName());
                ShellCommands.deleteBackup(backupSubDir);
            } else {
                Log.e(TAG, "PrefsActivity.deleteBackups: backupDir null");
            }
        }
        handleMessages.endMessage();
        NotificationHelper.showNotification(requireContext(), PrefsActivity.class, (int) System.currentTimeMillis(), getString(R.string.batchDeleteNotificationTitle), getString(R.string.batchDeleteBackupsDeleted) + " " + deleteList.size(), false);
    }
}
