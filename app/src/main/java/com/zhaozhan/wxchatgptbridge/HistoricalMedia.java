package com.zhaozhan.wxchatgptbridge;

/** 指向同一会话中某一条历史消息的媒体内容。 */
final class HistoricalMedia {
    final int messageIndex;
    final String extension;
    final byte[] bytes;
    final boolean image;

    HistoricalMedia(int messageIndex, RecentMediaCache.Entry entry) {
        this.messageIndex = messageIndex;
        this.extension = entry.extension;
        this.bytes = entry.bytes;
        this.image = entry.image;
    }
}
