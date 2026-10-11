package com.whyun.witv.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.whyun.witv.data.db.AppDatabase;
import com.whyun.witv.data.db.entity.Channel;
import com.whyun.witv.data.db.entity.M3USource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

@RunWith(RobolectricTestRunner.class)
public class ChannelRepositoryTest {

    private static final String TWO_CHANNELS = "#EXTM3U\n"
            + "#EXTINF:-1 tvg-id=\"cctv1\",CCTV-1\n"
            + "http://example.com/cctv1\n"
            + "#EXTINF:-1 tvg-id=\"cctv2\",CCTV-2\n"
            + "http://example.com/cctv2\n";

    private AppDatabase db;
    private M3USource source;
    /** 假源站下一次返回的内容 */
    private String playlist;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        source = new M3USource("test", "http://example.com/list.m3u", null, 0L, true);
        source.id = db.m3uSourceDao().insert(source);
    }

    @After
    public void tearDown() {
        db.close();
    }

    private ChannelRepository repository() {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(ResponseBody.create(playlist,
                                MediaType.get("audio/x-mpegurl; charset=utf-8")))
                        .build())
                .build();
        return new ChannelRepository(db, client);
    }

    @Test
    public void loadSourceSavesParsedChannels() throws IOException {
        playlist = TWO_CHANNELS;
        repository().loadSource(source);

        List<Channel> channels = db.channelDao().getBySource(source.id);
        assertEquals(2, channels.size());
        assertEquals(1, db.channelSourceDao().getByChannel(channels.get(0).id).size());
    }

    /** 源站临时返回空页面时不能把本地缓存的频道删光——播放页是先用缓存起播的。 */
    @Test
    public void emptyPlaylistKeepsCachedChannels() throws IOException {
        playlist = TWO_CHANNELS;
        repository().loadSource(source);

        playlist = "#EXTM3U\n";
        try {
            repository().loadSource(source);
            fail("Empty playlist should be reported as a failed refresh");
        } catch (IOException expected) {
            // 按刷新失败处理
        }

        List<Channel> channels = db.channelDao().getBySource(source.id);
        assertEquals(2, channels.size());
        assertEquals(1, db.channelSourceDao().getByChannel(channels.get(0).id).size());
    }
}
