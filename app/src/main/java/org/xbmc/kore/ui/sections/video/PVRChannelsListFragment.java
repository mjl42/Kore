/*
 * Copyright 2015 Synced Synapse. All rights reserved.
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
package org.xbmc.kore.ui.sections.video;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.xbmc.kore.R;
import org.xbmc.kore.host.HostManager;
import org.xbmc.kore.jsonrpc.ApiCallback;
import org.xbmc.kore.jsonrpc.ApiException;
import org.xbmc.kore.jsonrpc.method.PVR;
import org.xbmc.kore.jsonrpc.method.Player;
import org.xbmc.kore.jsonrpc.type.ItemType;
import org.xbmc.kore.jsonrpc.type.PVRType;
import org.xbmc.kore.ui.AbstractSearchableFragment;
import org.xbmc.kore.ui.OnBackPressedListener;
import org.xbmc.kore.ui.viewgroups.RecyclerViewEmptyViewSupport;
import org.xbmc.kore.utils.LogUtils;
import org.xbmc.kore.utils.UIUtils;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Fragment that presents the movie list
 */
public class PVRChannelsListFragment extends AbstractSearchableFragment
        implements SwipeRefreshLayout.OnRefreshListener, OnBackPressedListener {
    private static final String TAG = LogUtils.makeLogTag(PVRChannelsListFragment.class);

    public static final String CHANNELGROUPID = "channelgroupid";
    public static final String SINGLECHANNELGROUP = "singlechannelgroup";

    public interface OnPVRChannelSelectedListener {
        void onChannelGuideSelected(int channelId, String channelTitle, boolean singleChannelGroup);
        void onChannelGroupSelected(int channelGroupId, String channelGroupTitle);
    }

    // Activity listener
    private OnPVRChannelSelectedListener listenerActivity;

    private HostManager hostManager;

    /**
     * Handler on which to post RPC callbacks
     */
    private Handler callbackHandler = new Handler();

    private int selectedChannelGroupId = -1;
    private int currentListType;
    private boolean singleChannelGroup = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected RecyclerView.Adapter createAdapter() {
        return new PVRChannelsListFragment.ChannelAdapter(getActivity());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = super.onCreateView(inflater, container, savedInstanceState);

        if (savedInstanceState != null) {
            selectedChannelGroupId = savedInstanceState.getInt(CHANNELGROUPID);
            singleChannelGroup = savedInstanceState.getBoolean(SINGLECHANNELGROUP);
        }

        hostManager = HostManager.getInstance(getActivity());

        currentListType = getArguments().getInt(PVRListFragment.PVR_LIST_TYPE_KEY, PVRListFragment.LIST_TV_CHANNELS);

        getEmptyView().setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRefresh();
            }
        });

        return root;
    }

    @Override
    public void onActivityCreated (Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setSupportsSearch(true);

        if (selectedChannelGroupId == -1) {

            ChannelAdapter adapter = (ChannelAdapter) getAdapter();

            if ((adapter == null) ||
                    (adapter.getGroupItemCount() == 0))
                browseChannelGroups();
        } else {
            browseChannels(selectedChannelGroupId);
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            listenerActivity = (OnPVRChannelSelectedListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString() + " must implement OnPVRChannelSelectedListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        listenerActivity = null;
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onSaveInstanceState (Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(CHANNELGROUPID, selectedChannelGroupId);
        outState.putBoolean(SINGLECHANNELGROUP, singleChannelGroup);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    @Override
    protected void refreshList() {
        onRefresh();
    }

    /** {@inheritDoc} */
    @Override
    public void onRefresh () {
        if (hostManager.getHostInfo() != null) {
            if (selectedChannelGroupId == -1) {
                browseChannelGroups();
            } else {
                browseChannels(selectedChannelGroupId);
            }
        } else {
            hideRefreshAnimation();
            Toast.makeText(getActivity(), R.string.no_xbmc_configured, Toast.LENGTH_SHORT)
                 .show();
        }
    }

    /**
     * Called by the viewpager fragment
     *
     * @return True if back eas handled, false if it wasn't
     */
    public boolean onBackPressed() {
        if (!singleChannelGroup && (selectedChannelGroupId != -1)) {
            selectedChannelGroupId = -1;
            browseChannelGroups();
            return true;
        }
        return false;
    }

    /**
     * Get the channel groups list and setup the gridview
     */
    private void browseChannelGroups() {
        LogUtils.LOGD(TAG, "Getting channel groups");
        String channelType = (currentListType == PVRListFragment.LIST_TV_CHANNELS)?
                PVRType.ChannelType.TV : PVRType.ChannelType.RADIO;
        PVR.GetChannelGroups action = new PVR.GetChannelGroups(channelType);
        action.execute(hostManager.getConnection(), new ApiCallback<List<PVRType.DetailsChannelGroup>>() {
            @Override
            public void onSuccess(List<PVRType.DetailsChannelGroup> result) {
                if (!isAdded()) return;
                LogUtils.LOGD(TAG, "Got channel groups");

                if (result.size() == 1) {
                    // Single channel group, go directly to channel list
                    singleChannelGroup = true;
                    selectedChannelGroupId = result.get(0).channelgroupid;
                    browseChannels(selectedChannelGroupId);
                } else {
                    // To prevent the empty text from appearing on the first load, set it now
                    getEmptyView().setText(getString(R.string.no_channel_groups_found_refresh));
                    setupChannelGroupsGridview(result);
                    hideRefreshAnimation();
                }
            }

            @Override
            public void onError(int errorCode, String description) {
                if (!isAdded()) return;
                LogUtils.LOGD(TAG, "Error getting channel groups: " + description);

                if (errorCode == ApiException.API_ERROR) {
                    getEmptyView().setText(getString(R.string.might_not_have_pvr));
                } else {
                    getEmptyView().setText(String.format(getString(R.string.error_getting_pvr_info), description));
                }
                Toast.makeText(getActivity(),
                               String.format(getString(R.string.error_getting_pvr_info), description),
                               Toast.LENGTH_SHORT).show();
                hideRefreshAnimation();
            }
        }, callbackHandler);
    }

    private List<PVRType.DetailsChannel> filter(List<PVRType.DetailsChannel> itemList) {
        String searchFilter = getSearchFilter();

        if (TextUtils.isEmpty(searchFilter)) {
            return itemList;
        }

        // Split searchFilter to multiple lowercase words
        String[] lcWords = searchFilter.toLowerCase().split(" ");

        List<PVRType.DetailsChannel> result = new ArrayList<>(itemList.size());
        for (PVRType.DetailsChannel item:itemList) {
            // Require all words to match the item:
            boolean allWordsMatch = true;
            for (String lcWord:lcWords) {
                if (!searchFilterWordMatches(lcWord, item)) {
                    allWordsMatch = false;
                    break;
                }
            }
            if (!allWordsMatch) {
                continue; // skip this item
            }

            result.add(item);
        }

        return result;
    }

    public boolean searchFilterWordMatches(String lcWord, PVRType.DetailsChannel item) {
        if (item.label.toLowerCase().contains(lcWord)) {
            return true;
        }
        if (item.broadcastnow != null && item.broadcastnow.title.toLowerCase().contains(lcWord)){
            return true;
        }
        return false;
    }

    @Override
    protected RecyclerViewEmptyViewSupport.OnItemClickListener createOnItemClickListener() {
        return new RecyclerViewEmptyViewSupport.OnItemClickListener() {
            @Override
            public void onItemClick(View view, int position) {
                Object tag = view.getTag();

                if (tag == null)
                {
                    return;
                }

                if (tag instanceof ChannelGroupViewHolder) {
                    // Get the id from the tag
                    ChannelGroupViewHolder holder = (ChannelGroupViewHolder) view.getTag();
                    selectedChannelGroupId = holder.channelGroupId;
                    // Notify the activity and show the channels
                    listenerActivity.onChannelGroupSelected(holder.channelGroupId, holder.channelGroupName);
                    browseChannels(holder.channelGroupId);
                } else {
                    ChannelViewHolder holder = (ChannelViewHolder) tag;

                    // Start the channel
                    Toast.makeText(getActivity(),
                            String.format(getString(R.string.channel_switching), holder.channelName),
                            Toast.LENGTH_SHORT).show();
                    Player.Open action = new Player.Open(Player.Open.TYPE_CHANNEL, holder.channelId);
                    action.execute(hostManager.getConnection(), new ApiCallback<String>() {
                        @Override
                        public void onSuccess(String result) {
                            if (!isAdded()) return;
                            LogUtils.LOGD(TAG, "Started channel");
                        }

                        @Override
                        public void onError(int errorCode, String description) {
                            if (!isAdded()) return;
                            LogUtils.LOGD(TAG, "Error starting channel: " + description);

                            Toast.makeText(getActivity(),
                                    String.format(getString(R.string.error_starting_channel), description),
                                    Toast.LENGTH_SHORT).show();

                        }
                    }, callbackHandler);
                }
            }
        };
    }

    /**
     * Called when we get the channel groups
     *
     * @param result ChannelGroups obtained
     */
    private void setupChannelGroupsGridview(List<PVRType.DetailsChannelGroup> result) {
        ChannelAdapter channelAdapter = (ChannelAdapter) getAdapter();
        channelAdapter.setGroupItems(result);
    }

    /**
     * Gets and displays the channels of a channelgroup
     * @param channelGroupId id
     */
    private void browseChannels(final int channelGroupId) {
        String[] properties = PVRType.FieldsChannel.allValues;
        LogUtils.LOGD(TAG, "Getting channels");

        PVR.GetChannels action = new PVR.GetChannels(channelGroupId, properties);
        action.execute(hostManager.getConnection(), new ApiCallback<List<PVRType.DetailsChannel>>() {
            @Override
            public void onSuccess(List<PVRType.DetailsChannel> result) {
                if (!isAdded()) return;
                LogUtils.LOGD(TAG, "Got channels");

                // To prevent the empty text from appearing on the first load, set it now
                getEmptyView().setText(getString(R.string.no_channels_found_refresh));

                List<PVRType.DetailsChannel> finalResult = filter(result);

                setupChannelsGridview(finalResult);
                hideRefreshAnimation();
            }

            @Override
            public void onError(int errorCode, String description) {
                if (!isAdded()) return;
                LogUtils.LOGD(TAG, "Error getting channels: " + description);

                // To prevent the empty text from appearing on the first load, set it now
                getEmptyView().setText(String.format(getString(R.string.error_getting_pvr_info), description));
                Toast.makeText(getActivity(),
                               String.format(getString(R.string.error_getting_pvr_info), description),
                               Toast.LENGTH_SHORT).show();
                hideRefreshAnimation();
            }
        }, callbackHandler);

    }

    /**
     * Called when we get the channels
     *
     * @param result Channels obtained
     */
    private void setupChannelsGridview(List<PVRType.DetailsChannel> result) {
        ChannelAdapter channelAdapter = (ChannelAdapter) getAdapter();
        channelAdapter.setItems(result);
    }

    private class ChannelAdapter extends RecyclerView.Adapter {

        private HostManager hostManager;
        private int artWidth, artHeight;
        private Context context;
        private List<ItemType.DetailsBase> items;

        public ChannelAdapter(Context context) {
            super();
            this.hostManager = HostManager.getInstance(context);
            this.context = context;

            Resources resources = context.getResources();
            artWidth = (int) (resources.getDimension(R.dimen.channellist_art_width) /
                    UIUtils.IMAGE_RESIZE_FACTOR);
            artHeight = (int) (resources.getDimension(R.dimen.channellist_art_heigth) /
                    UIUtils.IMAGE_RESIZE_FACTOR);
        }

        protected int getSectionColumnIdx() {
            return 0;
        }

        @Override
        public int getItemViewType(int position) {
            return getItem(position) instanceof PVRType.DetailsChannelGroup ? 0 : 1;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup viewGroup, int i) {

            RecyclerView.ViewHolder viewHolder;

            if (i == 0) {
                View view = LayoutInflater.from(context)
                        .inflate(R.layout.grid_item_channel_group, viewGroup, false);

                viewHolder = new PVRChannelsListFragment.ChannelGroupViewHolder(view);
            } else {
                View view = LayoutInflater.from(context)
                        .inflate(R.layout.grid_item_channel, viewGroup, false);

                viewHolder = new PVRChannelsListFragment.ChannelViewHolder(view);
            }

            return viewHolder;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof ChannelGroupViewHolder) {
                PVRType.DetailsChannelGroup item = (PVRType.DetailsChannelGroup) this.getItem(position);
                ((PVRChannelsListFragment.ChannelGroupViewHolder) holder).bindView(item, getContext());
            } else {
                PVRType.DetailsChannel item = (PVRType.DetailsChannel) this.getItem(position);
                ((PVRChannelsListFragment.ChannelViewHolder) holder).bindView(item, getContext(), hostManager, artWidth, artHeight, channelItemMenuClickListener);
            }
        }

        /**
         * Manually set the items on the adapter
         * Calls notifyDataSetChanged()
         *
         * @param channelDetails list of channel details
         */
        public void setItems(List<PVRType.DetailsChannel> channelDetails) {
            this.items = new LinkedList<>();

            items.addAll(channelDetails);

            notifyDataSetChanged();
        }

        public void setGroupItems(List<PVRType.DetailsChannelGroup> channelGroupDetails) {
            this.items = new LinkedList<>();

            items.addAll(channelGroupDetails);

            notifyDataSetChanged();
        }

        public List<ItemType.DetailsBase> getItemList() {
            if (items == null)
                return new ArrayList<>();
            return new ArrayList<>(items);
        }

        public ItemType.DetailsBase getItem(int position) {
            if (items == null) {
                return null;
            } else {
                return items.get(position);
            }
        }

        private View.OnClickListener channelItemMenuClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final ChannelViewHolder viewHolder = (ChannelViewHolder)v.getTag();
                final int channelId = viewHolder.channelId;
                final String channelName = viewHolder.channelName;

                final PopupMenu popupMenu = new PopupMenu(getActivity(), v);
                popupMenu.getMenuInflater().inflate(R.menu.pvr_channel_list_item, popupMenu.getMenu());
                popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        switch (item.getItemId()) {
                            case R.id.action_record_item:
                                PVR.Record action = new PVR.Record(channelId);
                                action.execute(hostManager.getConnection(), new ApiCallback<String>() {
                                    @Override
                                    public void onSuccess(String result) {
                                        if (!isAdded()) return;
                                        LogUtils.LOGD(TAG, "Started recording");
                                    }

                                    @Override
                                    public void onError(int errorCode, String description) {
                                        if (!isAdded()) return;
                                        LogUtils.LOGD(TAG, "Error starting to record: " + description);

                                        Toast.makeText(getActivity(),
                                                       String.format(getString(R.string.error_starting_to_record), description),
                                                       Toast.LENGTH_SHORT).show();

                                    }
                                }, callbackHandler);
                                return true;
                            case R.id.action_epg_item:
                                listenerActivity.onChannelGuideSelected(channelId, channelName, singleChannelGroup);
                                return true;
                        }
                        return false;
                    }
                });
                popupMenu.show();
            }
        };

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getItemCount() {
            if (items == null) {
                return 0;
            } else {
                return items.size();
            }
        }

        public int getGroupItemCount() {
            if (items == null) {
                return 0;
            }

            int count = 0;

            for (ItemType.DetailsBase item : items) {
                if (item instanceof PVRType.DetailsChannelGroup)
                    count++;
            }

            return count;
        }
    }

    private abstract static class ChannelBaseViewHolder extends RecyclerView.ViewHolder {
        public ChannelBaseViewHolder(View itemView) {
            super(itemView);
        }
    }

    /**
     * View holder pattern
     */
    private static class ChannelGroupViewHolder extends ChannelBaseViewHolder {
        TextView titleView;

        int channelGroupId;
        String channelGroupName;

        public ChannelGroupViewHolder(View itemView) {
            super(itemView);

            titleView = (TextView) itemView.findViewById(R.id.title);
        }

        public void bindView(PVRType.DetailsChannelGroup channelGroupDetails, Context context) {
            channelGroupId = channelGroupDetails.channelgroupid;
            channelGroupName = channelGroupDetails.label;

            titleView.setText(UIUtils.applyMarkup(context, channelGroupName));

            itemView.setTag(this);
        }
    }

    /**
     * View holder pattern
     */
    private static class ChannelViewHolder extends ChannelBaseViewHolder {
        TextView titleView, detailsView;
        ImageView artView, contextMenu;

        int channelId;
        String channelName;

        public ChannelViewHolder(View itemView) {
            super(itemView);

            titleView = (TextView) itemView.findViewById(R.id.title);
            detailsView = (TextView) itemView.findViewById(R.id.details);
            artView = (ImageView) itemView.findViewById(R.id.art);
            contextMenu = (ImageView) itemView.findViewById(R.id.list_context_menu);
        }

        public void bindView(PVRType.DetailsChannel channelDetails, Context context, HostManager hostManager, int artWidth, int artHeight, View.OnClickListener channelItemMenuClickListener) {
            channelId = channelDetails.channelid;
            channelName = channelDetails.channel;

            titleView.setText(UIUtils.applyMarkup(context, channelDetails.channel));
            String details = (channelDetails.broadcastnow != null) ?
                    channelDetails.broadcastnow.title : null;
            detailsView.setText(UIUtils.applyMarkup(context, details));
            UIUtils.loadImageWithCharacterAvatar(context, hostManager,
                    channelDetails.thumbnail, channelDetails.channel,
                    artView, artWidth, artHeight);

            // For the popupmenu
            contextMenu.setTag(this);
            contextMenu.setOnClickListener(channelItemMenuClickListener);

            itemView.setTag(this);
        }
    }
}
