/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.packageinstaller.permission.ui.handheld;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PermissionInfo;
import android.content.pm.UsesPermissionInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;

import com.android.packageinstaller.permission.model.AppPermissionGroup;
import com.android.packageinstaller.permission.model.Permission;
import com.android.packageinstaller.permission.model.PermissionUsages;
import com.android.packageinstaller.permission.utils.IconDrawableFactory;
import com.android.packageinstaller.permission.utils.LocationUtils;
import com.android.packageinstaller.permission.utils.PackageRemovalMonitor;
import com.android.packageinstaller.permission.utils.SafetyNetLogger;
import com.android.packageinstaller.permission.utils.Utils;
import com.android.permissioncontroller.R;
import com.android.settingslib.RestrictedLockUtils;
import com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.List;

/**
 * Show and manage a single permission group for an app.
 *
 * <p>Allows the user to control whether the app is granted the permission.
 */
public class AppPermissionFragment extends SettingsWithButtonHeader {
    private static final String LOG_TAG = "AppPermissionFragment";

    @Retention(SOURCE)
    @IntDef(value = {CHANGE_FOREGROUND, CHANGE_BACKGROUND}, flag = true)
    @interface ChangeTarget {}
    static final int CHANGE_FOREGROUND = 1;
    static final int CHANGE_BACKGROUND = 2;
    static final int CHANGE_BOTH = CHANGE_FOREGROUND | CHANGE_BACKGROUND;

    private @NonNull AppPermissionGroup mGroup;

    private @NonNull RadioGroup mRadioGroup;
    private @NonNull RadioButton mAlwaysButton;
    private @NonNull RadioButton mForegroundOnlyButton;
    private @NonNull RadioButton mDenyButton;
    private @NonNull View mDivider;
    private @NonNull ViewGroup mWidgetFrame;
    private @NonNull TextView mPermissionDetails;

    private boolean mHasConfirmedRevoke;

    /**
     * Listens for changes to the permission of the app the permission is currently getting
     * granted to. {@code null} when unregistered.
     */
    private @Nullable PackageManager.OnPermissionsChangedListener mPermissionChangeListener;

    /**
     * Listens for changes to the app the permission is currently getting granted to. {@code null}
     * when unregistered.
     */
    private @Nullable PackageRemovalMonitor mPackageRemovalMonitor;

    /**
     * @return A new fragment
     */
    public static @NonNull AppPermissionFragment newInstance(@NonNull String packageName,
            @NonNull String permissionName, @NonNull UserHandle userHandle) {
        AppPermissionFragment fragment = new AppPermissionFragment();
        Bundle arguments = new Bundle();
        arguments.putString(Intent.EXTRA_PACKAGE_NAME, packageName);
        arguments.putString(Intent.EXTRA_PERMISSION_NAME, permissionName);
        arguments.putParcelable(Intent.EXTRA_USER, userHandle);
        fragment.setArguments(arguments);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        ActionBar ab = getActivity().getActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
        }

        mHasConfirmedRevoke = false;

        createAppPermissionGroup();

        if (mGroup != null) {
            getActivity().setTitle(
                    getPreferenceManager().getContext().getString(R.string.app_permission_title,
                            mGroup.getLabel()));
        }
    }

    private void createAppPermissionGroup() {
        Activity activity = getActivity();
        Context context = getPreferenceManager().getContext();

        String packageName = getArguments().getString(Intent.EXTRA_PACKAGE_NAME);
        String groupName = getArguments().getString(Intent.EXTRA_PERMISSION_NAME);
        PackageItemInfo groupInfo = Utils.getGroupInfo(groupName, context);
        List<PermissionInfo> groupPermInfos = Utils.getGroupPermissionInfos(groupName, context);
        if (groupInfo == null || groupPermInfos == null) {
            Log.i(LOG_TAG, "Illegal group: " + groupName);
            activity.setResult(Activity.RESULT_CANCELED);
            activity.finish();
            return;
        }
        UserHandle userHandle = getArguments().getParcelable(Intent.EXTRA_USER);
        mGroup = AppPermissionGroup.create(context,
                getPackageInfo(activity, packageName, userHandle),
                groupInfo, groupPermInfos, false);

        if (mGroup == null || !Utils.shouldShowPermission(context, mGroup)) {
            Log.i(LOG_TAG, "Illegal group: " + (mGroup == null ? "null" : mGroup.getName()));
            activity.setResult(Activity.RESULT_CANCELED);
            activity.finish();
            return;
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        Context context = getPreferenceManager().getContext();
        ViewGroup root = (ViewGroup) inflater.inflate(R.layout.app_permission, container, false);

        if (mGroup == null) {
            return root;
        }

        String appLabel = Utils.getFullAppLabel(mGroup.getApp().applicationInfo, context);
        setHeader(getAppIcon(), appLabel);
        updateHeader(root.requireViewById(R.id.button_header));

        ((TextView) root.requireViewById(R.id.permission_message)).setText(
                context.getString(R.string.app_permission_header, mGroup.getLabel(), appLabel));

        String timeDiffStr = Utils.getLastUsageString(context,
                PermissionUsages.loadLastGroupUsage(context, mGroup));
        if (timeDiffStr == null) {
            ((TextView) root.requireViewById(R.id.usage_summary)).setText(
                    context.getString(
                            R.string.app_permission_footer_no_usages,
                            appLabel,
                            mGroup.getLabel().toString().toLowerCase()));
        } else {
            ((TextView) root.requireViewById(R.id.usage_summary)).setText(
                    context.getString(
                            R.string.app_permission_footer_usage_summary,
                            appLabel,
                            mGroup.getLabel().toString().toLowerCase(),
                            timeDiffStr));
        }

        root.requireViewById(R.id.usage_link).setOnClickListener((v) -> {
            Intent intent = new Intent(Intent.ACTION_REVIEW_APP_PERMISSION_USAGE);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, mGroup.getApp().packageName);
            context.startActivity(intent);
        });

        mRadioGroup = root.requireViewById(R.id.radiogroup);
        mAlwaysButton = root.requireViewById(R.id.allow_radio_button);
        mForegroundOnlyButton = root.requireViewById(R.id.foreground_only_radio_button);
        mDenyButton = root.requireViewById(R.id.deny_radio_button);
        mDivider = root.requireViewById(R.id.two_target_divider);
        mWidgetFrame = root.requireViewById(R.id.widget_frame);
        mPermissionDetails = root.requireViewById(R.id.permission_details);

        updateButtons();
        updateJustification(context, root, appLabel);

        return root;
    }

    @Override
    public void onStart() {
        super.onStart();

        if (mGroup == null) {
            return;
        }

        String packageName = getArguments().getString(Intent.EXTRA_PACKAGE_NAME);
        UserHandle userHandle = getArguments().getParcelable(Intent.EXTRA_USER);
        Activity activity = getActivity();

        // Get notified when permissions change.
        try {
            mPermissionChangeListener = new PermissionChangeListener(
                    mGroup.getApp().applicationInfo.uid);
        } catch (NameNotFoundException e) {
            activity.setResult(Activity.RESULT_CANCELED);
            activity.finish();
            return;
        }
        PackageManager pm = activity.getPackageManager();
        pm.addOnPermissionsChangeListener(mPermissionChangeListener);

        // Get notified when the package is removed.
        mPackageRemovalMonitor = new PackageRemovalMonitor(getContext(), packageName) {
            @Override
            public void onPackageRemoved() {
                Log.w(LOG_TAG, packageName + " was uninstalled");
                activity.setResult(Activity.RESULT_CANCELED);
                activity.finish();
            }
        };
        mPackageRemovalMonitor.register();

        // Check if the package was removed while this activity was not started.
        try {
            activity.createPackageContextAsUser(
                    packageName, 0, userHandle).getPackageManager().getPackageInfo(packageName, 0);
        } catch (NameNotFoundException e) {
            Log.w(LOG_TAG, packageName + " was uninstalled while this activity was stopped", e);
            activity.setResult(Activity.RESULT_CANCELED);
            activity.finish();
        }
    }

    @Override
    public void onStop() {
        super.onStop();

        if (mPackageRemovalMonitor != null) {
            mPackageRemovalMonitor.unregister();
            mPackageRemovalMonitor = null;
        }

        if (mPermissionChangeListener != null) {
            getActivity().getPackageManager().removeOnPermissionsChangeListener(
                    mPermissionChangeListener);
            mPermissionChangeListener = null;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getActivity().finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateButtons() {
        Context context = getContext();

        // Reset everything to the "default" state: tri-state buttons are shown with exactly one
        // selected and no special messages.
        mDivider.setVisibility(View.GONE);
        mWidgetFrame.setVisibility(View.GONE);
        mPermissionDetails.setVisibility(View.GONE);

        if (mGroup.areRuntimePermissionsGranted()) {
            if (!mGroup.hasPermissionWithBackgroundMode()
                    || (mGroup.getBackgroundPermissions() != null
                    && mGroup.getBackgroundPermissions().areRuntimePermissionsGranted())) {
                setCheckedButton(mAlwaysButton);
            } else {
                setCheckedButton(mForegroundOnlyButton);
            }
        } else {
            setCheckedButton(mDenyButton);
        }

        mAlwaysButton.setOnClickListener((v) -> requestChange(true, CHANGE_BOTH));
        mForegroundOnlyButton.setOnClickListener((v) -> {
            requestChange(false, CHANGE_BACKGROUND);
            requestChange(true, CHANGE_FOREGROUND);
        });
        mDenyButton.setOnClickListener((v) -> requestChange(false, CHANGE_BOTH));

        // Set the allow and foreground-only button states appropriately.
        if (mGroup.hasPermissionWithBackgroundMode()) {
            if (mGroup.getBackgroundPermissions() == null) {
                mAlwaysButton.setVisibility(View.GONE);
            } else {
                mForegroundOnlyButton.setVisibility(View.VISIBLE);
                mAlwaysButton.setText(
                        context.getString(R.string.app_permission_button_allow_always));
            }
        } else {
            mForegroundOnlyButton.setVisibility(View.GONE);
            mAlwaysButton.setText(context.getString(R.string.app_permission_button_allow));
        }

        // Handle the UI for various special cases.
        if (isSystemFixed() || isPolicyFullyFixed() || isForegroundDisabledByPolicy()) {
            // Disable changing permissions and potentially show administrator message.
            mAlwaysButton.setEnabled(false);
            mForegroundOnlyButton.setEnabled(false);
            mDenyButton.setEnabled(false);

            EnforcedAdmin admin = getAdmin();
            if (admin != null) {
                showRightIcon(R.drawable.ic_info);
                mWidgetFrame.setOnClickListener(v ->
                        RestrictedLockUtils.sendShowAdminSupportDetailsIntent(context, admin)
                );
            }

            updateDetailForFixedByPolicyPermissionGroup();
        } else if (Utils.areGroupPermissionsIndividuallyControlled(context, mGroup.getName())) {
            // If the permissions are individually controlled, also show a link to the page that
            // lets you control them.
            mDivider.setVisibility(View.VISIBLE);
            showRightIcon(R.drawable.ic_settings);
            mWidgetFrame.setOnClickListener(v -> showAllPermissions(mGroup.getName()));

            updateDetailForIndividuallyControlledPermissionGroup();
        } else {
            if (mGroup.hasPermissionWithBackgroundMode()) {
                if (mGroup.getBackgroundPermissions() == null) {
                    // The group has background permissions but the app did not request any. I.e.
                    // The app can only switch between 'never" and "only in foreground".
                    mAlwaysButton.setEnabled(false);

                    mDenyButton.setOnClickListener((v) -> requestChange(false, CHANGE_FOREGROUND));
                } else {
                    if (isBackgroundPolicyFixed()) {
                        // If background policy is fixed, we only allow switching the foreground.
                        // Note that this assumes that the background policy is fixed to deny,
                        // since if it is fixed to grant, so is the foreground.
                        mAlwaysButton.setEnabled(false);
                        setCheckedButton(mForegroundOnlyButton);

                        mDenyButton.setOnClickListener(
                                (v) -> requestChange(false, CHANGE_FOREGROUND));

                        updateDetailForFixedByPolicyPermissionGroup();
                    } else if (isForegroundPolicyFixed()) {
                        // Foreground permissions are fixed to allow (the first case above handles
                        // fixing to deny), so we only allow toggling background permissions.
                        mDenyButton.setEnabled(false);

                        mAlwaysButton.setOnClickListener(
                                (v) -> requestChange(true, CHANGE_BACKGROUND));
                        mForegroundOnlyButton.setOnClickListener(
                                (v) -> requestChange(false, CHANGE_BACKGROUND));

                        updateDetailForFixedByPolicyPermissionGroup();
                    } else {
                        // The default tri-state case is handled by default.
                    }
                }

            } else {
                // The default bi-state case is handled by default.
            }
        }
    }

    private void updateJustification(Context context, ViewGroup root, @NonNull String appLabel) {
        // Collect the names of the permissions of the group.
        ArrayList<Permission> groupPerms = mGroup.getPermissions();
        ArraySet<String> permissions = new ArraySet(groupPerms.size());
        for (int i = 0; i < groupPerms.size(); i++) {
            Permission permission = groupPerms.get(i);
            if (permission.getPermissionInfo().usageInfoRequired) {
                permissions.add(permission.getName());
            }
        }

        // If no permissions require usage information, show nothing.
        if (permissions.isEmpty()) {
            root.requireViewById(R.id.justification_all).setVisibility(View.GONE);
            return;
        }

        int sentOffDevice = UsesPermissionInfo.USAGE_NO;
        int sharedWithThirdParty = UsesPermissionInfo.USAGE_NO;
        int usedForMonetization = UsesPermissionInfo.USAGE_NO;
        int retention = UsesPermissionInfo.RETENTION_NOT_RETAINED;
        int retentionWeeks = -1;

        // Compute the strongest justification by this app.
        UsesPermissionInfo[] justifications = mGroup.getApp().usesPermissions;
        for (int i = 0; i < justifications.length; i++) {
            UsesPermissionInfo justification = justifications[i];

            // Only consider permissions in this group.
            if (!permissions.contains(justification.getPermission())) {
                continue;
            }

            sentOffDevice = Math.min(sentOffDevice, justification.getDataSentOffDevice());
            sharedWithThirdParty = Math.min(sharedWithThirdParty,
                    justification.getDataSharedWithThirdParty());
            usedForMonetization = Math.min(usedForMonetization,
                    justification.getDataUsedForMonetization());
            retention = compareRetentions(retention, justification.getDataRetention());
            if (justification.getDataRetention() == UsesPermissionInfo.RETENTION_SPECIFIED) {
                retentionWeeks = Math.max(retentionWeeks, justification.getDataRetentionWeeks());
            }
        }

        if (isSystemFixed() || isPolicyFullyFixed()) {
            root.requireViewById(R.id.justification_footer).setVisibility(View.GONE);
        }

        if (sentOffDevice == UsesPermissionInfo.USAGE_UNDEFINED
                || sharedWithThirdParty == UsesPermissionInfo.USAGE_UNDEFINED
                || usedForMonetization == UsesPermissionInfo.USAGE_UNDEFINED
                || retention == UsesPermissionInfo.RETENTION_UNDEFINED) {
            // If at least one permission is undefined, only show the generic undefined string.
            root.requireViewById(R.id.justification_entries).setVisibility(View.GONE);
            ((TextView) root.requireViewById(R.id.justification_header)).setText(
                    context.getString(R.string.permission_justification_undefined));
        } else {
            // Show/hide or set the text of the various text views.
            ((TextView) root.requireViewById(R.id.justification_header)).setText(
                    context.getString(R.string.permission_justification_header));
            setUsageText(root.requireViewById(R.id.justification_sent_off_device),
                    root.requireViewById(R.id.justification_sent_off_device_text), sentOffDevice,
                    R.string.permission_justification_data_sent_off_device,
                    R.string.permission_justification_data_sent_off_device_user_triggered, context);
            setUsageText(root.requireViewById(R.id.justification_shared_with_third_party),
                    root.requireViewById(R.id.justification_shared_with_third_party_text),
                    sharedWithThirdParty,
                    R.string.permission_justification_data_shared_with_third_party,
                    R.string.permission_justification_data_shared_with_third_party_user_triggered,
                    context);
            setUsageText(root.requireViewById(R.id.justification_used_for_monetization),
                    root.requireViewById(R.id.justification_used_for_monetization_text),
                    usedForMonetization,
                    R.string.permission_justification_data_used_for_monetization,
                    R.string.permission_justification_data_used_for_monetization_user_triggered,
                    context);
            if (retention == UsesPermissionInfo.RETENTION_NOT_RETAINED) {
                root.requireViewById(R.id.justification_retention).setVisibility(View.GONE);
            } else if (retention == UsesPermissionInfo.RETENTION_USER_SELECTED) {
                ((TextView) root.requireViewById(R.id.justification_retention_text)).setText(
                        context.getString(
                                R.string.permission_justification_data_retention_user_selected));
            } else if (retention == UsesPermissionInfo.RETENTION_SPECIFIED) {
                ((TextView) root.requireViewById(R.id.justification_retention_text)).setText(
                        context.getResources().getQuantityString(
                                R.plurals.permission_justification_data_retention_specified,
                                retentionWeeks,
                                retentionWeeks));
            } else {
                ((TextView) root.requireViewById(R.id.justification_retention_text)).setText(
                        context.getString(
                                R.string.permission_justification_data_retention_unlimited));
            }
        }

        TextView infoIntentView = root.requireViewById(R.id.justification_info_intent);
        Intent infoIntent = new Intent(Intent.ACTION_PERMISSION_USAGE_DETAILS);
        infoIntent.setPackage(mGroup.getApp().packageName);
        if (infoIntent.resolveActivity(context.getPackageManager()) != null) {
            infoIntentView.setText(context.getString(R.string.permission_justification_info_intent,
                    appLabel));
            infoIntentView.setOnClickListener((v) -> {
                infoIntent.putExtra(Intent.EXTRA_PERMISSION_USAGE_PERMISSIONS,
                        permissions.toArray(new String[permissions.size()]));
                context.startActivity(infoIntent);
            });
        } else {
            infoIntentView.setVisibility(View.GONE);
        }
    }

    /**
     * Compare two UsesPermissionInfo.Retention values and return the stronger.
     *
     * @param r1 a Retention value
     * @param r2 another Retention value
     * @return the stronger of the two values
     */
    private static int compareRetentions(int r1, int r2) {
        if (r1 == UsesPermissionInfo.RETENTION_UNDEFINED
                || r2 == UsesPermissionInfo.RETENTION_UNDEFINED) {
            return UsesPermissionInfo.RETENTION_UNDEFINED;
        }
        if (r1 == UsesPermissionInfo.RETENTION_UNLIMITED
                || r2 == UsesPermissionInfo.RETENTION_UNLIMITED) {
            return UsesPermissionInfo.RETENTION_UNLIMITED;
        }
        if (r1 == UsesPermissionInfo.RETENTION_SPECIFIED
                || r2 == UsesPermissionInfo.RETENTION_SPECIFIED) {
            return UsesPermissionInfo.RETENTION_SPECIFIED;
        }
        if (r1 == UsesPermissionInfo.RETENTION_USER_SELECTED
                || r2 == UsesPermissionInfo.RETENTION_USER_SELECTED) {
            return UsesPermissionInfo.RETENTION_USER_SELECTED;
        }
        if (r1 == UsesPermissionInfo.RETENTION_NOT_RETAINED
                || r2 == UsesPermissionInfo.RETENTION_NOT_RETAINED) {
            return UsesPermissionInfo.RETENTION_NOT_RETAINED;
        }
        Log.w(LOG_TAG, "Invalid retention: " + r1 + ", " + r2);
        return UsesPermissionInfo.RETENTION_UNDEFINED;
    }

    /**
     * Set the text of the given view to one of the given values or hide it if necessary.
     *
     * @param container the view that contains the given text view.
     * @param textView the TextView to change
     * @param value the value that controls which text to use
     * @param yesStrId the resId of the string to use if the usage is allowed
     * @param userTriggeredStrId the resId of the string to use if the usage is allowed on user-
     * triggered actions.
     * @param context the context
     */
    private void setUsageText(View container, TextView textView, int value, int yesStrId,
            int userTriggeredStrId, Context context) {
        if (value == UsesPermissionInfo.USAGE_NO) {
            container.setVisibility(View.GONE);
        } else if (value == UsesPermissionInfo.USAGE_USER_TRIGGERED) {
            textView.setText(context.getString(userTriggeredStrId));
        } else {
            textView.setText(context.getString(yesStrId));
        }
    }

    /**
     * Set the given button as the only checked button in the radio group.
     *
     * @param button the button that should be checked.
     */
    private void setCheckedButton(@NonNull RadioButton button) {
        mRadioGroup.clearCheck();
        button.setChecked(true);
        if (button != mAlwaysButton) {
            mAlwaysButton.setChecked(false);
        }
        if (button != mForegroundOnlyButton) {
            mForegroundOnlyButton.setChecked(false);
        }
        if (button != mDenyButton) {
            mDenyButton.setChecked(false);
        }
    }

    /**
     * Show the given icon on the right of the first radio button.
     *
     * @param iconId the resourceId of the drawable to use.
     */
    private void showRightIcon(int iconId) {
        mWidgetFrame.removeAllViews();
        ImageView imageView = new ImageView(getPreferenceManager().getContext());
        imageView.setImageResource(iconId);
        mWidgetFrame.addView(imageView);
        mWidgetFrame.setVisibility(View.VISIBLE);
    }

    private static @Nullable PackageInfo getPackageInfo(@NonNull Activity activity,
            @NonNull String packageName, @NonNull UserHandle userHandle) {
        try {
            return activity.createPackageContextAsUser(packageName, 0,
                    userHandle).getPackageManager().getPackageInfo(packageName,
                    PackageManager.GET_PERMISSIONS);
        } catch (PackageManager.NameNotFoundException e) {
            Log.i(LOG_TAG, "No package: " + activity.getCallingPackage(), e);
            activity.setResult(Activity.RESULT_CANCELED);
            activity.finish();
            return null;
        }
    }

    /**
     * Are any permissions of this group fixed by the system, i.e. not changeable by the user.
     *
     * @return {@code true} iff any permission is fixed
     */
    private boolean isSystemFixed() {
        return mGroup.isSystemFixed();
    }

    /**
     * Is any foreground permissions of this group fixed by the policy, i.e. not changeable by the
     * user.
     *
     * @return {@code true} iff any foreground permission is fixed
     */
    private boolean isForegroundPolicyFixed() {
        return mGroup.isPolicyFixed();
    }

    /**
     * Is any background permissions of this group fixed by the policy, i.e. not changeable by the
     * user.
     *
     * @return {@code true} iff any background permission is fixed
     */
    private boolean isBackgroundPolicyFixed() {
        return mGroup.getBackgroundPermissions() != null
                && mGroup.getBackgroundPermissions().isPolicyFixed();
    }

    /**
     * Are there permissions fixed, so that the user cannot change the preference at all?
     *
     * @return {@code true} iff the permissions of this group are fixed
     */
    private boolean isPolicyFullyFixed() {
        return isForegroundPolicyFixed() && (mGroup.getBackgroundPermissions() == null
                || isBackgroundPolicyFixed());
    }

    /**
     * Is the foreground part of this group disabled. If the foreground is disabled, there is no
     * need to possible grant background access.
     *
     * @return {@code true} iff the permissions of this group are fixed
     */
    private boolean isForegroundDisabledByPolicy() {
        return isForegroundPolicyFixed() && !mGroup.areRuntimePermissionsGranted();
    }

    /**
     * Get the app that acts as admin for this profile.
     *
     * @return The admin or {@code null} if there is no admin.
     */
    private @Nullable EnforcedAdmin getAdmin() {
        return RestrictedLockUtils.getProfileOrDeviceOwner(getContext(), mGroup.getUser());
    }

    /**
     * Update the detail in the case the permission group has individually controlled permissions.
     */
    private void updateDetailForIndividuallyControlledPermissionGroup() {
        int revokedCount = 0;
        List<Permission> permissions = mGroup.getPermissions();
        int permissionCount = permissions.size();
        for (int i = 0; i < permissionCount; i++) {
            Permission permission = permissions.get(i);
            if (!permission.isGrantedIncludingAppOp()) {
                revokedCount++;
            }
        }

        int resId;
        if (revokedCount == 0) {
            resId = R.string.permission_revoked_none;
        } else if (revokedCount == permissionCount) {
            resId = R.string.permission_revoked_all;
        } else {
            resId = R.string.permission_revoked_count;
        }

        mPermissionDetails.setText(getContext().getString(resId, revokedCount));
        mPermissionDetails.setVisibility(View.VISIBLE);
    }

    /**
     * Update the detail of a permission group that is at least partially fixed by policy.
     */
    private void updateDetailForFixedByPolicyPermissionGroup() {
        EnforcedAdmin admin = getAdmin();
        AppPermissionGroup backgroundGroup = mGroup.getBackgroundPermissions();

        boolean hasAdmin = admin != null;

        if (isSystemFixed()) {
            // Permission is fully controlled by the system and cannot be switched

            setDetail(R.string.permission_summary_enabled_system_fixed);
        } else if (isForegroundDisabledByPolicy()) {
            // Permission is fully controlled by policy and cannot be switched

            if (hasAdmin) {
                setDetail(R.string.disabled_by_admin);
            } else {
                // Disabled state will be displayed by switch, so no need to add text for that
                setDetail(R.string.permission_summary_enforced_by_policy);
            }
        } else if (isPolicyFullyFixed()) {
            // Permission is fully controlled by policy and cannot be switched

            if (backgroundGroup == null) {
                if (hasAdmin) {
                    setDetail(R.string.enabled_by_admin);
                } else {
                    // Enabled state will be displayed by switch, so no need to add text for
                    // that
                    setDetail(R.string.permission_summary_enforced_by_policy);
                }
            } else {
                if (backgroundGroup.areRuntimePermissionsGranted()) {
                    if (hasAdmin) {
                        setDetail(R.string.enabled_by_admin);
                    } else {
                        // Enabled state will be displayed by switch, so no need to add text for
                        // that
                        setDetail(R.string.permission_summary_enforced_by_policy);
                    }
                } else {
                    if (hasAdmin) {
                        setDetail(
                                R.string.permission_summary_enabled_by_admin_foreground_only);
                    } else {
                        setDetail(
                                R.string.permission_summary_enabled_by_policy_foreground_only);
                    }
                }
            }
        } else {
            // Part of the permission group can still be switched

            if (isBackgroundPolicyFixed()) {
                if (backgroundGroup.areRuntimePermissionsGranted()) {
                    if (hasAdmin) {
                        setDetail(R.string.permission_summary_enabled_by_admin_background_only);
                    } else {
                        setDetail(R.string.permission_summary_enabled_by_policy_background_only);
                    }
                } else {
                    if (hasAdmin) {
                        setDetail(R.string.permission_summary_disabled_by_admin_background_only);
                    } else {
                        setDetail(R.string.permission_summary_disabled_by_policy_background_only);
                    }
                }
            } else if (isForegroundPolicyFixed()) {
                if (hasAdmin) {
                    setDetail(R.string.permission_summary_enabled_by_admin_foreground_only);
                } else {
                    setDetail(R.string.permission_summary_enabled_by_policy_foreground_only);
                }
            }
        }
    }

    /**
     * Show the given string as informative text below the radio buttons.
     * @param strId the resourceId of the string to display.
     */
    private void setDetail(int strId) {
        mPermissionDetails.setText(getPreferenceManager().getContext().getString(strId));
        mPermissionDetails.setVisibility(View.VISIBLE);
    }

    /**
     * Show all individual permissions in this group in a new fragment.
     */
    private void showAllPermissions(@NonNull String filterGroup) {
        Fragment frag = AllAppPermissionsFragment.newInstance(mGroup.getApp().packageName,
                filterGroup);
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, frag)
                .addToBackStack("AllPerms")
                .commit();
    }

    /**
     * Get the icon of this app.
     *
     * @return the app's icon.
     */
    private @NonNull Drawable getAppIcon() {
        ApplicationInfo appInfo = mGroup.getApp().applicationInfo;
        return IconDrawableFactory.getBadgedIcon(getActivity(), appInfo,
                UserHandle.getUserHandleForUid(appInfo.uid));
    }

    /**
     * Request to grant/revoke permissions group.
     *
     * <p>Does <u>not</u> handle:
     * <ul>
     * <li>Individually granted permissions</li>
     * <li>Permission groups with background permissions</li>
     * </ul>
     * <p><u>Does</u> handle:
     * <ul>
     * <li>Default grant permissions</li>
     * </ul>
     *
     * @param requestGrant If this group should be granted
     * @param changeTarget Which permission group (foreground/background/both) should be changed
     *
     * @return If the request was processed.
     */
    private boolean requestChange(boolean requestGrant, @ChangeTarget int changeTarget) {
        if (LocationUtils.isLocationGroupAndProvider(getContext(), mGroup.getName(),
                mGroup.getApp().packageName)) {
            LocationUtils.showLocationDialog(getContext(),
                    Utils.getAppLabel(mGroup.getApp().applicationInfo, getContext()));

            // The request was denied, so update the buttons.
            updateButtons();
            return false;
        }

        if (requestGrant) {
            if ((changeTarget & CHANGE_FOREGROUND) != 0) {
                if (!mGroup.areRuntimePermissionsGranted()) {
                    SafetyNetLogger.logPermissionToggled(mGroup);
                }

                mGroup.grantRuntimePermissions(false);
            }
            if ((changeTarget & CHANGE_BACKGROUND) != 0) {
                if (mGroup.getBackgroundPermissions() != null) {
                    if (!mGroup.getBackgroundPermissions().areRuntimePermissionsGranted()) {
                        SafetyNetLogger.logPermissionToggled(mGroup.getBackgroundPermissions());
                    }

                    mGroup.getBackgroundPermissions().grantRuntimePermissions(false);
                }
            }
        } else {
            boolean requestToRevokeGrantedByDefault = false;

            if ((changeTarget & CHANGE_FOREGROUND) != 0
                    && mGroup.areRuntimePermissionsGranted()) {
                requestToRevokeGrantedByDefault = mGroup.hasGrantedByDefaultPermission();
            }
            if ((changeTarget & CHANGE_BACKGROUND) != 0) {
                if (mGroup.getBackgroundPermissions() != null
                        && mGroup.getBackgroundPermissions().areRuntimePermissionsGranted()) {
                    requestToRevokeGrantedByDefault |=
                            mGroup.getBackgroundPermissions().hasGrantedByDefaultPermission();
                }
            }

            if ((requestToRevokeGrantedByDefault || !mGroup.doesSupportRuntimePermissions())
                    && !mHasConfirmedRevoke) {
                showDefaultDenyDialog(changeTarget);
                updateButtons();
                return false;
            } else {
                if ((changeTarget & CHANGE_FOREGROUND) != 0
                        && mGroup.areRuntimePermissionsGranted()) {
                    if (mGroup.areRuntimePermissionsGranted()) {
                        SafetyNetLogger.logPermissionToggled(mGroup);
                    }

                    mGroup.revokeRuntimePermissions(false);
                }
                if ((changeTarget & CHANGE_BACKGROUND) != 0) {
                    if (mGroup.getBackgroundPermissions() != null
                            && mGroup.getBackgroundPermissions().areRuntimePermissionsGranted()) {
                        if (mGroup.getBackgroundPermissions().areRuntimePermissionsGranted()) {
                            SafetyNetLogger.logPermissionToggled(mGroup.getBackgroundPermissions());
                        }

                        mGroup.getBackgroundPermissions().revokeRuntimePermissions(false);
                    }
                }
            }
        }

        updateButtons();

        return true;
    }

    /**
     * Show a dialog that warns the user that she/he is about to revoke permissions that were
     * granted by default.
     *
     * <p>The order of operation to revoke a permission granted by default is:
     * <ol>
     *     <li>{@code showDefaultDenyDialog}</li>
     *     <li>{@link DefaultDenyDialog#onCreateDialog}</li>
     *     <li>{@link AppPermissionFragment#onDenyAnyWay}</li>
     * </ol>
     *
     * @param changeTarget Whether background or foreground should be changed
     */
    private void showDefaultDenyDialog(@ChangeTarget int changeTarget) {
        Bundle args = new Bundle();

        boolean showGrantedByDefaultWarning = false;
        if ((changeTarget & CHANGE_FOREGROUND) != 0) {
            showGrantedByDefaultWarning = mGroup.hasGrantedByDefaultPermission();
        }
        if ((changeTarget & CHANGE_BACKGROUND) != 0) {
            if (mGroup.getBackgroundPermissions() != null) {
                showGrantedByDefaultWarning |=
                        mGroup.getBackgroundPermissions().hasGrantedByDefaultPermission();
            }
        }

        args.putInt(DefaultDenyDialog.MSG, showGrantedByDefaultWarning ? R.string.system_warning
                : R.string.old_sdk_deny_warning);
        args.putInt(DefaultDenyDialog.CHANGE_TARGET, changeTarget);

        DefaultDenyDialog defaultDenyDialog = new DefaultDenyDialog(this);
        defaultDenyDialog.setArguments(args);
        defaultDenyDialog.show(getFragmentManager().beginTransaction(),
                "denyDefault");
    }

    /**
     * Once we user has confirmed that he/she wants to revoke a permission that was granted by
     * default, actually revoke the permissions.
     *
     * @param changeTarget whether to change foreground, background, or both.
     *
     * @see #showDefaultDenyDialog(int)
     */
    void onDenyAnyWay(@ChangeTarget int changeTarget) {
        boolean hasDefaultPermissions = false;
        if ((changeTarget & CHANGE_FOREGROUND) != 0) {
            if (mGroup.areRuntimePermissionsGranted()) {
                SafetyNetLogger.logPermissionToggled(mGroup);
            }

            mGroup.revokeRuntimePermissions(false);
            hasDefaultPermissions = mGroup.hasGrantedByDefaultPermission();
        }
        if ((changeTarget & CHANGE_BACKGROUND) != 0) {
            if (mGroup.getBackgroundPermissions() != null) {
                if (mGroup.getBackgroundPermissions().areRuntimePermissionsGranted()) {
                    SafetyNetLogger.logPermissionToggled(mGroup.getBackgroundPermissions());
                }

                mGroup.getBackgroundPermissions().revokeRuntimePermissions(false);
                hasDefaultPermissions |=
                        mGroup.getBackgroundPermissions().hasGrantedByDefaultPermission();
            }
        }

        if (hasDefaultPermissions || !mGroup.doesSupportRuntimePermissions()) {
            mHasConfirmedRevoke = true;
        }
        updateButtons();
    }

    /**
     * A dialog warning the user that she/he is about to deny a permission that was granted by
     * default.
     *
     * @see #showDefaultDenyDialog(int)
     */
    public static class DefaultDenyDialog extends DialogFragment {
        private static final String MSG = DefaultDenyDialog.class.getName() + ".arg.msg";
        private static final String CHANGE_TARGET = DefaultDenyDialog.class.getName()
                + ".arg.changeTarget";
        private static final String KEY = DefaultDenyDialog.class.getName() + ".arg.key";

        private @NonNull AppPermissionFragment mFragment;

        public DefaultDenyDialog(@NonNull AppPermissionFragment fragment) {
            mFragment = fragment;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            AlertDialog.Builder b = new AlertDialog.Builder(getContext())
                    .setMessage(getArguments().getInt(MSG))
                    .setNegativeButton(R.string.cancel,
                            (DialogInterface dialog, int which) -> mFragment.updateButtons())
                    .setPositiveButton(R.string.grant_dialog_button_deny_anyway,
                            (DialogInterface dialog, int which) ->
                                    mFragment.onDenyAnyWay(getArguments().getInt(CHANGE_TARGET)));

            return b.create();
        }
    }

    /**
     * A listener for permission changes.
     */
    private class PermissionChangeListener implements PackageManager.OnPermissionsChangedListener {
        private final int mUid;

        PermissionChangeListener(int uid) throws NameNotFoundException {
            mUid = uid;
        }

        @Override
        public void onPermissionsChanged(int uid) {
            if (uid == mUid) {
                Log.w(LOG_TAG, "Permissions changed.");
                createAppPermissionGroup();
                updateButtons();
            }
        }
    }
}
