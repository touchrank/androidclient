/*
 * Kontalk Android client
 * Copyright (C) 2011 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.ui;

import org.kontalk.R;
import org.kontalk.data.Contact;
import org.kontalk.data.Conversation;
import org.kontalk.provider.MyMessages.Messages;
import org.kontalk.util.MessageUtils;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.AttributeSet;
import android.widget.ImageView;
import android.widget.QuickContactBadge;
import android.widget.RelativeLayout;
import android.widget.TextView;

public class ConversationListItem extends RelativeLayout {
    //private static final String TAG = ConversationListItem.class.getSimpleName();

    private static final StyleSpan STYLE_BOLD = new StyleSpan(Typeface.BOLD);

    private Conversation mConversation;
    private TextView mSubjectView;
    private TextView mFromView;
    private TextView mDateView;
    //private View mAttachmentView;
    private ImageView mErrorIndicator;
    //private ImageView mPresenceView;
    private QuickContactBadge mAvatarView;

    static private Drawable sDefaultContactImage;

    public ConversationListItem(Context context) {
        super(context);
    }

    public ConversationListItem(Context context, AttributeSet attrs) {
        super(context, attrs);

        if (sDefaultContactImage == null) {
            sDefaultContactImage = context.getResources().getDrawable(R.drawable.ic_contact_picture);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mFromView = (TextView) findViewById(R.id.from);
        mSubjectView = (TextView) findViewById(R.id.subject);

        mDateView = (TextView) findViewById(R.id.date);
        //mAttachmentView = findViewById(R.id.attachment);
        mErrorIndicator = (ImageView) findViewById(R.id.error);
        //mPresenceView = (ImageView) findViewById(R.id.presence);
        mAvatarView = (QuickContactBadge) findViewById(R.id.avatar);

        if (isInEditMode()) {
            mFromView.setText("Test zio (3)");
            mSubjectView.setText("Bella zio senti per domani facciamo che mi vieni a prendere ok?");
            mDateView.setText("20:14");
            mAvatarView.setVisibility(VISIBLE);
            mAvatarView.setImageResource(R.drawable.ic_contact_picture);
            mErrorIndicator.setVisibility(VISIBLE);
            mErrorIndicator.setImageResource(R.drawable.ic_thread_pending);
        }
    }

    /**
     * Only used for header binding.
     */
    public void bind(String title, String explain) {
        mFromView.setText(title);
        mSubjectView.setText(explain);
    }

    public final void bind(Context context, final Conversation conv) {
        mConversation = conv;
        String recipient = null;

        Contact contact = mConversation.getContact();

        if (contact != null) {
            recipient = contact.getName();
            mAvatarView.assignContactUri(contact.getUri());
            mAvatarView.setImageDrawable(contact.getAvatar(context, sDefaultContactImage));
        }
        else {
            // FIXME debug mode -- recipient = conv.getRecipient();
            recipient = context.getString(R.string.peer_unknown);
            mAvatarView.setImageDrawable(sDefaultContactImage);
        }
        mAvatarView.setVisibility(VISIBLE);

        SpannableStringBuilder from = new SpannableStringBuilder(recipient);
        if (conv.getMessageCount() > 1)
            from.append(" (" + conv.getMessageCount() + ") ");

        if (conv.getUnreadCount() > 0)
            from.setSpan(STYLE_BOLD, 0, from.length(), Spannable.SPAN_INCLUSIVE_EXCLUSIVE);

        // draft indicator
        int lastpos = from.length();
        String draft = conv.getDraft();
        if (draft != null) {
            from.append(" ");
            from.append(context.getResources().getString(R.string.has_draft));
            from.setSpan(new ForegroundColorSpan(
                    context.getResources().getColor(R.drawable.text_color_red)),
                    lastpos, from.length(), Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
        }

        mFromView.setText(from);
        mDateView.setText(MessageUtils.formatTimeStampString(context, conv.getDate()));

        // last message or draft??
        mSubjectView.setText(draft != null ? draft : conv.getSubject());

        // error indicator
        int resId = -1;
        switch (conv.getStatus()) {
            case Messages.STATUS_SENDING:
                resId = R.drawable.ic_thread_pending;
                break;
            case Messages.STATUS_SENT:
                resId = R.drawable.ic_msg_sent;
                break;
            case Messages.STATUS_RECEIVED:
                resId = R.drawable.ic_msg_delivered;
                break;
            case Messages.STATUS_ERROR:
            case Messages.STATUS_NOTACCEPTED:
                resId = R.drawable.ic_thread_error;
                break;
            case Messages.STATUS_NOTDELIVERED:
                resId = R.drawable.ic_msg_notdelivered;
                break;
        }

        // no matching resource or draft - hide status icon
        if (resId < 0 || mConversation.getDraft() != null) {
            mErrorIndicator.setVisibility(INVISIBLE);
        }
        else {
            mErrorIndicator.setVisibility(VISIBLE);
            mErrorIndicator.setImageResource(resId);
        }
    }

    public final void unbind() {
        // TODO unbind (contact?)
    }

    public Conversation getConversation() {
        return mConversation;
    }

}
