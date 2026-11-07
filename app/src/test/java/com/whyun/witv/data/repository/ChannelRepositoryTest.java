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

    private static final String WITH_TVG_URL = "#EXTM3U x-tvg-url=\"http://m3u.example.com/e.xml\"\n"
            + "#EXTINF:-1 tvg-id=\"cctv1\",CCTV-1\n"
            + "http://example.com/cctv1\n";

    /** 刷新直播源只写「直播源内」的地址：用户选了自定义时，生效地址不能被 x-tvg-url 覆盖回去。 */
    @Test
    public void reloadKeepsCustomEpgWhenSwitchedToCustom() throws IOException {
        M3USource row = db.m3uSourceDao().getById(source.id);
        row.customEpgUrl = "http://custom.example.com/e.xml";
        row.useCustomEpg = true;
        row.applyEffectiveEpgUrl();
        db.m3uSourceDao().update(row);

        playlist = WITH_TVG_URL;
        // 故意传旧对象（没有自定义地址），模拟播放页启动时缓存的 source
        repository().loadSource(source);

        M3USource saved = db.m3uSourceDao().getById(source.id);
        assertEquals("http://m3u.example.com/e.xml", saved.m3uEpgUrl);
        assertEquals("http://custom.example.com/e.xml", saved.customEpgUrl);
        assertEquals("http://custom.example.com/e.xml", saved.epgUrl);
        assertEquals("http://custom.example.com/e.xml", source.epgUrl);
    }

    @Test
    public void reloadUsesM3uEpgWhenSwitchedToM3u() throws IOException {
        M3USource row = db.m3uSourceDao().getById(source.id);
        row.customEpgUrl = "http://custom.example.com/e.xml";
        row.useCustomEpg = false;
        db.m3uSourceDao().update(row);

        playlist = WITH_TVG_URL;
        repository().loadSource(source);

        M3USource saved = db.m3uSourceDao().getById(source.id);
        assertEquals("http://m3u.example.com/e.xml", saved.epgUrl);
        assertEquals("http://custom.example.com/e.xml", saved.customEpgUrl);
    }
}
