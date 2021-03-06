package org.wordpress.android.ui.notifications;

import android.app.Activity;
import android.app.Fragment;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.cocosw.undobar.UndoBarController;
import com.simperium.client.Bucket;
import com.simperium.client.BucketObject;
import com.simperium.client.BucketObjectMissingException;

import org.wordpress.android.GCMIntentService;
import org.wordpress.android.R;
import org.wordpress.android.analytics.AnalyticsTracker;
import org.wordpress.android.models.CommentStatus;
import org.wordpress.android.models.Note;
import org.wordpress.android.ui.RequestCodes;
import org.wordpress.android.ui.main.WPMainActivity;
import org.wordpress.android.ui.comments.CommentActions;
import org.wordpress.android.ui.notifications.adapters.NotesAdapter;
import org.wordpress.android.ui.notifications.utils.SimperiumUtils;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.StringUtils;
import org.wordpress.android.util.ToastUtils;
import org.wordpress.android.util.helpers.SwipeToRefreshHelper;
import org.wordpress.android.util.widgets.CustomSwipeRefreshLayout;

import javax.annotation.Nonnull;

public class NotificationsListFragment extends Fragment
        implements Bucket.Listener<Note>,
                   WPMainActivity.OnScrollToTopListener {
    public static final String NOTE_ID_EXTRA = "noteId";
    public static final String NOTE_INSTANT_REPLY_EXTRA = "instantReply";
    public static final String NOTE_MODERATE_ID_EXTRA = "moderateNoteId";
    public static final String NOTE_MODERATE_STATUS_EXTRA = "moderateNoteStatus";

    private static final String KEY_LIST_SCROLL_POSITION = "scrollPosition";

    private SwipeToRefreshHelper mFauxSwipeToRefreshHelper;
    private NotesAdapter mNotesAdapter;
    private LinearLayoutManager mLinearLayoutManager;
    private RecyclerView mRecyclerView;
    private TextView mEmptyTextView;

    private int mRestoredScrollPosition;

    private Bucket<Note> mBucket;

    public static NotificationsListFragment newInstance() {
        return new NotificationsListFragment();
   }

    /**
     * For responding to tapping of notes
     */
    public interface OnNoteClickListener {
        public void onClickNote(String noteId);
    }

    @Override
    public View onCreateView(@Nonnull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.notifications_fragment_notes_list, container, false);

        mRecyclerView = (RecyclerView) view.findViewById(R.id.recycler_view_notes);
        RecyclerView.ItemAnimator animator = new DefaultItemAnimator();
        animator.setSupportsChangeAnimations(true);
        mRecyclerView.setItemAnimator(animator);
        mLinearLayoutManager = new LinearLayoutManager(getActivity());
        mRecyclerView.setLayoutManager(mLinearLayoutManager);

        // setup the initial notes adapter, starts listening to the bucket
        mBucket = SimperiumUtils.getNotesBucket();
        if (mBucket != null) {
            if (mNotesAdapter == null) {
                mNotesAdapter = new NotesAdapter(getActivity(), mBucket);
                mNotesAdapter.setOnNoteClickListener(new OnNoteClickListener() {
                    @Override
                    public void onClickNote(String noteId) {
                        if (TextUtils.isEmpty(noteId)) return;

                        // open the latest version of this note just in case it has changed - this can
                        // happen if the note was tapped from the list fragment after it was updated
                        // by another fragment (such as NotificationCommentLikeFragment)
                        openNote(getActivity(), noteId, false);
                    }
                });
            }

            mRecyclerView.setAdapter(mNotesAdapter);
        } else {
            ToastUtils.showToast(getActivity(), R.string.error_refresh_notifications);
        }

        mEmptyTextView = (TextView) view.findViewById(R.id.empty_view);

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        initSwipeToRefreshHelper();

        if (savedInstanceState != null) {
            setRestoredListPosition(savedInstanceState.getInt(KEY_LIST_SCROLL_POSITION, RecyclerView.NO_POSITION));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshNotes();

        // start listening to bucket change events
        if (mBucket != null) {
            mBucket.addListener(this);
        }

        // Remove notification if it is showing when we resume this activity.
        NotificationManager notificationManager = (NotificationManager) getActivity().getSystemService(GCMIntentService.NOTIFICATION_SERVICE);
        notificationManager.cancel(GCMIntentService.PUSH_NOTIFICATION_ID);

        if (SimperiumUtils.isUserAuthorized()) {
            SimperiumUtils.startBuckets();
            AppLog.i(AppLog.T.NOTIFS, "Starting Simperium buckets");
        }
    }

    @Override
    public void onPause() {
        // unregister the listener
        if (mBucket != null) {
            mBucket.removeListener(this);
        }
        super.onPause();
    }

    @Override
    public void onDestroy() {
        // Close Simperium cursor
        if (mNotesAdapter != null) {
            mNotesAdapter.closeCursor();
        }

        super.onDestroy();
    }

    private void initSwipeToRefreshHelper() {
        mFauxSwipeToRefreshHelper = new SwipeToRefreshHelper(
                getActivity(),
                (CustomSwipeRefreshLayout) getActivity().findViewById(R.id.ptr_layout),
                new SwipeToRefreshHelper.RefreshListener() {
                    @Override
                    public void onRefreshStarted() {
                        // Show a fake refresh animation for a few seconds
                        new Handler().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (isAdded()) {
                                    mFauxSwipeToRefreshHelper.setRefreshing(false);
                                }
                            }
                        }, 2000);
                    }
                });
    }

    /**
     * Open a note fragment based on the type of note
     */
    public static void openNote(Activity activity, final String noteId, boolean shouldShowKeyboard) {
        if (noteId == null || activity == null) {
            return;
        }

        Intent detailIntent = new Intent(activity, NotificationsDetailActivity.class);
        detailIntent.putExtra(NOTE_ID_EXTRA, noteId);
        detailIntent.putExtra(NOTE_INSTANT_REPLY_EXTRA, shouldShowKeyboard);

        ActivityOptionsCompat options = ActivityOptionsCompat.makeCustomAnimation(
                activity,
                R.anim.reader_activity_slide_in,
                R.anim.do_nothing);
        ActivityCompat.startActivityForResult(activity, detailIntent, RequestCodes.NOTE_DETAIL, options.toBundle());

        AnalyticsTracker.track(AnalyticsTracker.Stat.NOTIFICATIONS_OPENED_NOTIFICATION_DETAILS);
    }

    private void setNoteIsHidden(String noteId, boolean isHidden) {
        if (mNotesAdapter == null) return;

        if (isHidden) {
            mNotesAdapter.addHiddenNoteId(noteId);
        } else {
            // Scroll the row into view if it isn't visible so the animation can be seen
            int notePosition = mNotesAdapter.getPositionForNote(noteId);
            if (notePosition != RecyclerView.NO_POSITION &&
                    mLinearLayoutManager.findFirstCompletelyVisibleItemPosition() > notePosition) {
                mLinearLayoutManager.scrollToPosition(notePosition);
            }

            mNotesAdapter.removeHiddenNoteId(noteId);
        }
    }

    private void setNoteIsModerating(String noteId, boolean isModerating) {
        if (mNotesAdapter == null) return;

        if (isModerating) {
            mNotesAdapter.addModeratingNoteId(noteId);
        } else {
            mNotesAdapter.removeModeratingNoteId(noteId);
        }
    }

    public void updateLastSeenTime() {
        // set the timestamp to now
        try {
            if (mNotesAdapter != null && mNotesAdapter.getCount() > 0 && SimperiumUtils.getMetaBucket() != null) {
                Note newestNote = mNotesAdapter.getNote(0);
                BucketObject meta = SimperiumUtils.getMetaBucket().get("meta");
                if (meta != null && newestNote != null) {
                    meta.setProperty("last_seen", newestNote.getTimestamp());
                    meta.save();
                }
            }
        } catch (BucketObjectMissingException e) {
            // try again later, meta is created by wordpress.com
        }
    }

    void refreshNotes() {
        if (!isAdded() || mNotesAdapter == null) {
            return;
        }

        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mNotesAdapter.reloadNotes();
                restoreListScrollPosition();
                mEmptyTextView.setVisibility(mNotesAdapter.getCount() == 0 ? View.VISIBLE : View.GONE);
            }
        });
    }

    private void restoreListScrollPosition() {
        if (isAdded() && mRecyclerView != null && mRestoredScrollPosition != RecyclerView.NO_POSITION
                && mRestoredScrollPosition < mNotesAdapter.getCount()) {
            // Restore scroll position in list
            mLinearLayoutManager.scrollToPosition(mRestoredScrollPosition);
            mRestoredScrollPosition = RecyclerView.NO_POSITION;
        }
    }

    @Override
    public void onSaveInstanceState(@Nonnull Bundle outState) {
        if (outState.isEmpty()) {
            outState.putBoolean("bug_19917_fix", true);
        }

        // Save list view scroll position
        outState.putInt(KEY_LIST_SCROLL_POSITION, getScrollPosition());

        super.onSaveInstanceState(outState);
    }

    private int getScrollPosition() {
        if (!isAdded() || mRecyclerView == null) {
            return RecyclerView.NO_POSITION;
        }

        return mLinearLayoutManager.findFirstVisibleItemPosition();
    }

    private void setRestoredListPosition(int listPosition) {
        mRestoredScrollPosition = listPosition;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == RequestCodes.NOTE_DETAIL && resultCode == Activity.RESULT_OK && data != null) {
            if (SimperiumUtils.getNotesBucket() == null) return;

            try {
                Note note = SimperiumUtils.getNotesBucket().get(StringUtils.notNullStr(data.getStringExtra(NOTE_MODERATE_ID_EXTRA)));
                CommentStatus commentStatus = CommentStatus.fromString(data.getStringExtra(NOTE_MODERATE_STATUS_EXTRA));
                moderateCommentForNote(note, commentStatus);
            } catch (BucketObjectMissingException e) {
                e.printStackTrace();
            }
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    private void moderateCommentForNote(final Note note, final CommentStatus newStatus) {
        if (!isAdded()) return;

        if (newStatus == CommentStatus.APPROVED || newStatus == CommentStatus.UNAPPROVED) {
            note.setLocalStatus(CommentStatus.toRESTString(newStatus));
            note.save();
            setNoteIsModerating(note.getId(), true);
            CommentActions.moderateCommentForNote(note, newStatus,
                    new CommentActions.CommentActionListener() {
                        @Override
                        public void onActionResult(boolean succeeded) {
                            if (!isAdded()) return;

                            setNoteIsModerating(note.getId(), false);

                            if (!succeeded) {
                                note.setLocalStatus(null);
                                note.save();
                                ToastUtils.showToast(getActivity(),
                                        R.string.error_moderate_comment,
                                        ToastUtils.Duration.LONG
                                );
                            }
                        }
                    });
        } else if (newStatus == CommentStatus.TRASH || newStatus == CommentStatus.SPAM) {
            setNoteIsHidden(note.getId(), true);
            // Show undo bar for trash or spam actions
            new UndoBarController.UndoBar(getActivity())
                    .message(newStatus == CommentStatus.TRASH ? R.string.comment_trashed : R.string.comment_spammed)
                    .listener(new UndoBarController.AdvancedUndoListener() {
                        @Override
                        public void onHide(Parcelable parcelable) {
                            // Deleted notifications in Simperium never come back, so we won't
                            // make the request until the undo bar fades away
                            CommentActions.moderateCommentForNote(note, newStatus,
                                    new CommentActions.CommentActionListener() {
                                        @Override
                                        public void onActionResult(boolean succeeded) {
                                            if (!isAdded()) return;

                                            if (!succeeded) {
                                                setNoteIsHidden(note.getId(), false);
                                                ToastUtils.showToast(getActivity(),
                                                        R.string.error_moderate_comment,
                                                        ToastUtils.Duration.LONG
                                                );
                                            }
                                        }
                                    });
                        }

                        @Override
                        public void onClear(@Nonnull Parcelable[] token) {
                            //noop
                        }

                        @Override
                        public void onUndo(Parcelable parcelable) {
                            setNoteIsHidden(note.getId(), false);
                        }
                    }).show();
        }
    }

    /**
     * Simperium bucket listener methods
     */
    @Override
    public void onSaveObject(Bucket<Note> bucket, final Note object) {
        refreshNotes();
    }

    @Override
    public void onDeleteObject(Bucket<Note> bucket, final Note object) {
        refreshNotes();
    }

    @Override
    public void onNetworkChange(Bucket<Note> bucket, final Bucket.ChangeType type, final String key) {
        // Reset the note's local status when a remote change is received
        if (type == Bucket.ChangeType.MODIFY) {
            try {
                Note note = bucket.get(key);
                if (note.isCommentType()) {
                    note.setLocalStatus(null);
                    note.save();
                }
            } catch (BucketObjectMissingException e) {
                AppLog.e(AppLog.T.NOTIFS, "Could not create note after receiving change.");
            }
        }

        refreshNotes();
    }

    @Override
    public void onBeforeUpdateObject(Bucket<Note> noteBucket, Note note) {
        //noop
    }

    @Override
    public void onScrollToTop() {
        if (isAdded() && getScrollPosition() > 0) {
            mLinearLayoutManager.smoothScrollToPosition(mRecyclerView, null, 0);
        }
    }

}
