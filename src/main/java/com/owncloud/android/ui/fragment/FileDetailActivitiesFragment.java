/*
 * Nextcloud Android client application
 *
 * @author Andy Scherzinger
 * Copyright (C) 2018 Andy Scherzinger
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU AFFERO GENERAL PUBLIC LICENSE
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU AFFERO GENERAL PUBLIC LICENSE for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.owncloud.android.ui.fragment;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.content.Context;
import android.graphics.PorterDuff;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.design.widget.TextInputEditText;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.owncloud.android.MainApp;
import com.owncloud.android.R;
import com.owncloud.android.authentication.AccountUtils;
import com.owncloud.android.datamodel.FileDataStorageManager;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.lib.common.OwnCloudAccount;
import com.owncloud.android.lib.common.OwnCloudClient;
import com.owncloud.android.lib.common.OwnCloudClientManagerFactory;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.lib.resources.activities.GetRemoteActivitiesOperation;
import com.owncloud.android.lib.resources.activities.models.RichObject;
import com.owncloud.android.lib.resources.files.FileVersion;
import com.owncloud.android.lib.resources.files.ReadFileVersionsOperation;
import com.owncloud.android.lib.resources.status.OCCapability;
import com.owncloud.android.lib.resources.status.OwnCloudVersion;
import com.owncloud.android.operations.CommentFileOperation;
import com.owncloud.android.ui.activity.ComponentsGetter;
import com.owncloud.android.ui.activity.FileActivity;
import com.owncloud.android.ui.adapter.ActivityAndVersionListAdapter;
import com.owncloud.android.ui.helpers.FileOperationsHelper;
import com.owncloud.android.ui.interfaces.ActivityListInterface;
import com.owncloud.android.ui.interfaces.VersionListInterface;
import com.owncloud.android.utils.ThemeUtils;

import java.io.IOException;
import java.util.ArrayList;

import butterknife.BindString;
import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import butterknife.Unbinder;

public class FileDetailActivitiesFragment extends Fragment implements ActivityListInterface, VersionListInterface.View {
    private static final String TAG = FileDetailActivitiesFragment.class.getSimpleName();

    private static final String ARG_FILE = "FILE";
    private static final String ARG_ACCOUNT = "ACCOUNT";

    private ActivityAndVersionListAdapter adapter;
    private Unbinder unbinder;
    private OwnCloudClient ownCloudClient;

    private OCFile file;
    private Account account;

    private String nextPageUrl;
    private boolean isLoadingActivities;

    @BindView(R.id.empty_list_view)
    public LinearLayout emptyContentContainer;

    @BindView(R.id.swipe_containing_list)
    public SwipeRefreshLayout swipeListRefreshLayout;

    @BindView(R.id.swipe_containing_empty)
    public SwipeRefreshLayout swipeEmptyListRefreshLayout;

    @BindView(R.id.empty_list_view_text)
    public TextView emptyContentMessage;

    @BindView(R.id.empty_list_view_headline)
    public TextView emptyContentHeadline;

    @BindView(R.id.empty_list_icon)
    public ImageView emptyContentIcon;

    @BindView(R.id.empty_list_progress)
    public ProgressBar emptyContentProgressBar;

    @BindView(android.R.id.list)
    public RecyclerView recyclerView;

    @BindView(R.id.commentInputField)
    public TextInputEditText commentInput;

    @BindString(R.string.activities_no_results_headline)
    public String noResultsHeadline;

    @BindString(R.string.activities_no_results_message)
    public String noResultsMessage;

    private boolean restoreFileVersionSupported;
    private String userId;
    private FileOperationsHelper operationsHelper;
    private VersionListInterface.CommentCallback callback;

    public static FileDetailActivitiesFragment newInstance(OCFile file, Account account) {
        FileDetailActivitiesFragment fragment = new FileDetailActivitiesFragment();
        Bundle args = new Bundle();
        args.putParcelable(ARG_FILE, file);
        args.putParcelable(ARG_ACCOUNT, account);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {

        file = getArguments().getParcelable(ARG_FILE);
        account = getArguments().getParcelable(ARG_ACCOUNT);

        if (savedInstanceState != null) {
            file = savedInstanceState.getParcelable(FileActivity.EXTRA_FILE);
            account = savedInstanceState.getParcelable(FileActivity.EXTRA_ACCOUNT);
        }

        View view = inflater.inflate(R.layout.file_details_activities_fragment, container, false);
        unbinder = ButterKnife.bind(this, view);

        setupView();

        onCreateSwipeToRefresh(swipeEmptyListRefreshLayout);
        onCreateSwipeToRefresh(swipeListRefreshLayout);

        fetchAndSetData(null);

        swipeListRefreshLayout.setOnRefreshListener(() -> onRefreshListLayout(swipeListRefreshLayout));
        swipeEmptyListRefreshLayout.setOnRefreshListener(() -> onRefreshListLayout(swipeEmptyListRefreshLayout));

        AccountManager accountManager = AccountManager.get(getContext());
        userId = accountManager.getUserData(account,
                com.owncloud.android.lib.common.accounts.AccountUtils.Constants.KEY_USER_ID);

        callback = new VersionListInterface.CommentCallback() {

            @Override
            public void onSuccess() {
                commentInput.getText().clear();
                fetchAndSetData(null);
            }

            @Override
            public void onError(int error) {
                Snackbar.make(recyclerView, error, Snackbar.LENGTH_LONG).show();
            }
        };

        commentInput.getBackground().setColorFilter(
                ThemeUtils.primaryAccentColor(getContext()),
                PorterDuff.Mode.SRC_ATOP
        );

        return view;
    }

    @OnClick(R.id.submitComment)
    public void submitComment() {
        if (commentInput.getText().toString().trim().length() > 0) {
            new SubmitCommentTask(commentInput.getText().toString(), userId, file.getLocalId(),
                    callback, ownCloudClient).execute();
        }
    }

    private void onRefreshListLayout(SwipeRefreshLayout refreshLayout) {
        setLoadingMessage();
        if (refreshLayout != null && refreshLayout.isRefreshing()) {
            refreshLayout.setRefreshing(false);
        }
        fetchAndSetData(null);
    }

    private void setLoadingMessage() {
        emptyContentHeadline.setText(R.string.file_list_loading);
        emptyContentMessage.setText("");
        emptyContentIcon.setVisibility(View.GONE);
        emptyContentProgressBar.setVisibility(View.VISIBLE);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unbinder.unbind();
    }

    private void setupView() {
        FileDataStorageManager storageManager = new FileDataStorageManager(account, requireActivity().getContentResolver());
        operationsHelper = ((ComponentsGetter) requireActivity()).getFileOperationsHelper();

        OCCapability capability = storageManager.getCapability(account.name);
        OwnCloudVersion serverVersion = AccountUtils.getServerVersion(account);
        restoreFileVersionSupported = capability.getFilesVersioning().isTrue() &&
                serverVersion.compareTo(OwnCloudVersion.nextcloud_14) >= 0;

        emptyContentProgressBar.getIndeterminateDrawable().setColorFilter(ThemeUtils.primaryAccentColor(getContext()),
                PorterDuff.Mode.SRC_IN);
        emptyContentIcon.setImageDrawable(getResources().getDrawable(R.drawable.ic_activity_light_grey));

        adapter = new ActivityAndVersionListAdapter(getContext(), this, this, storageManager);
        recyclerView.setAdapter(adapter);

        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());

        recyclerView.setLayoutManager(layoutManager);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);

                int visibleItemCount = recyclerView.getChildCount();
                int totalItemCount = layoutManager.getItemCount();
                int firstVisibleItemIndex = layoutManager.findFirstVisibleItemPosition();

                // synchronize loading state when item count changes
                if (!isLoadingActivities && (totalItemCount - visibleItemCount) <= (firstVisibleItemIndex + 5)
                        && nextPageUrl != null && !nextPageUrl.isEmpty()) {
                    // Almost reached the end, continue to load new activities
                    fetchAndSetData(nextPageUrl);
                }
            }
        });
    }

    public void reload() {
        fetchAndSetData(null);
    }

    /**
     * @param pageUrl String
     */
    private void fetchAndSetData(String pageUrl) {
        final Account currentAccount = AccountUtils.getCurrentOwnCloudAccount(MainApp.getAppContext());
        final Context context = MainApp.getAppContext();
        final FragmentActivity activity = getActivity();

        final SwipeRefreshLayout empty = swipeEmptyListRefreshLayout;
        final SwipeRefreshLayout list = swipeListRefreshLayout;


        Thread t = new Thread(() -> {
            OwnCloudAccount ocAccount;
            try {
                ocAccount = new OwnCloudAccount(currentAccount, context);
                ownCloudClient = OwnCloudClientManagerFactory.getDefaultSingleton().
                        getClientFor(ocAccount, MainApp.getAppContext());
                ownCloudClient.setOwnCloudVersion(AccountUtils.getServerVersion(currentAccount));
                isLoadingActivities = true;

                GetRemoteActivitiesOperation getRemoteNotificationOperation = new GetRemoteActivitiesOperation(
                        file.getLocalId());

                if (pageUrl != null) {
                    getRemoteNotificationOperation.setNextUrl(pageUrl);
                }

                Log_OC.d(TAG, "BEFORE getRemoteActivitiesOperation.execute");
                final RemoteOperationResult result = getRemoteNotificationOperation.execute(ownCloudClient);

                ArrayList<Object> versions = null;
                if (restoreFileVersionSupported) {
                    ReadFileVersionsOperation readFileVersionsOperation = new ReadFileVersionsOperation(
                            file.getLocalId(), userId);

                    RemoteOperationResult result1 = readFileVersionsOperation.execute(ownCloudClient);

                    versions = result1.getData();
                }

                if (result.isSuccess() && result.getData() != null) {
                    final ArrayList<Object> data = result.getData();
                    final ArrayList<Object> activitiesAndVersions = (ArrayList) data.get(0);

                    if (restoreFileVersionSupported && versions != null) {
                        activitiesAndVersions.addAll(versions);
                    }
                    nextPageUrl = (String) data.get(1);

                    activity.runOnUiThread(() -> {
                        populateList(activitiesAndVersions, pageUrl == null);
                        if (activitiesAndVersions.isEmpty()) {
                            setEmptyContent(noResultsHeadline, noResultsMessage);
                            list.setVisibility(View.GONE);
                            empty.setVisibility(View.VISIBLE);
                        } else {
                            empty.setVisibility(View.GONE);
                            list.setVisibility(View.VISIBLE);
                        }
                        isLoadingActivities = false;
                    });
                } else {
                    Log_OC.d(TAG, result.getLogMessage());
                    // show error
                    String logMessage = result.getLogMessage();
                    if (result.getHttpCode() == 304) {
                        logMessage = noResultsMessage;
                    }
                    final String finalLogMessage = logMessage;
                    activity.runOnUiThread(() -> {
                        setErrorContent(finalLogMessage);
                        isLoadingActivities = false;
                    });
                }

                hideRefreshLayoutLoader(activity);
            } catch (com.owncloud.android.lib.common.accounts.AccountUtils.AccountNotFoundException e) {
                Log_OC.e(TAG, "Account not found", e);
            } catch (IOException e) {
                Log_OC.e(TAG, "IO error", e);
            } catch (OperationCanceledException e) {
                Log_OC.e(TAG, "Operation has been canceled", e);
            } catch (AuthenticatorException e) {
                Log_OC.e(TAG, "Authentication Exception", e);
            }
        }
        );

        t.start();
    }

    private void populateList(ArrayList<Object> activities, boolean clear) {
        adapter.setActivityAndVersionItems(activities, ownCloudClient, clear);
    }

    private void setEmptyContent(String headline, String message) {
        if (emptyContentContainer != null && emptyContentMessage != null) {
            emptyContentIcon.setImageDrawable(requireContext().getResources().getDrawable(R.drawable.ic_activity_light_grey));
            emptyContentHeadline.setText(headline);
            emptyContentMessage.setText(message);

            emptyContentMessage.setVisibility(View.VISIBLE);
            emptyContentProgressBar.setVisibility(View.GONE);
            emptyContentIcon.setVisibility(View.VISIBLE);
        }
    }

    private void setErrorContent(String message) {
        if (emptyContentContainer != null && emptyContentMessage != null) {
            emptyContentHeadline.setText(R.string.common_error);
            emptyContentIcon.setImageDrawable(requireContext().getResources().getDrawable(R.drawable.ic_alert_octagon));
            emptyContentMessage.setText(message);

            emptyContentMessage.setVisibility(View.VISIBLE);
            emptyContentProgressBar.setVisibility(View.GONE);
            emptyContentIcon.setVisibility(View.VISIBLE);
        }
    }

    private void hideRefreshLayoutLoader(FragmentActivity activity) {
        activity.runOnUiThread(() -> {
            if (swipeListRefreshLayout != null) {
                swipeListRefreshLayout.setRefreshing(false);
            }
            if (swipeEmptyListRefreshLayout != null) {
                swipeEmptyListRefreshLayout.setRefreshing(false);
            }
            isLoadingActivities = false;
        });
    }

    protected void onCreateSwipeToRefresh(SwipeRefreshLayout refreshLayout) {
        int primaryColor = ThemeUtils.primaryColor(getContext());
        int darkColor = ThemeUtils.primaryDarkColor(getContext());
        int accentColor = ThemeUtils.primaryAccentColor(getContext());

        // Colors in animations
        refreshLayout.setColorSchemeColors(accentColor, primaryColor, darkColor);
    }

    @Override
    public void onActivityClicked(RichObject richObject) {
        // TODO implement activity click
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putParcelable(FileActivity.EXTRA_FILE, file);
        outState.putParcelable(FileActivity.EXTRA_ACCOUNT, account);
    }

    @Override
    public void onRestoreClicked(FileVersion fileVersion) {
        operationsHelper.restoreFileVersion(fileVersion, userId);
    }

    private static class SubmitCommentTask extends AsyncTask<Void, Void, Boolean> {

        private String message;
        private String userId;
        private String fileId;
        private VersionListInterface.CommentCallback callback;
        private OwnCloudClient client;

        private SubmitCommentTask(String message, String userId, String fileId,
                                  VersionListInterface.CommentCallback callback, OwnCloudClient client) {
            this.message = message;
            this.userId = userId;
            this.fileId = fileId;
            this.callback = callback;
            this.client = client;
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            CommentFileOperation commentFileOperation = new CommentFileOperation(message, fileId, userId);

            RemoteOperationResult result = commentFileOperation.execute(client);

            return result.isSuccess();
        }

        @Override
        protected void onPostExecute(Boolean success) {
            super.onPostExecute(success);

            if (success) {
                callback.onSuccess();
            } else {
                callback.onError(R.string.error_comment_file);

            }
        }
    }
}