// Web 管理页的中英文文案。
// 语言优先取用户在页面上手动选择的（localStorage），否则跟随浏览器语言：zh* 用中文，其余用英文。
const I18N = {
    zh: {
        'page.title': 'WiTV 管理',
        'header.title': '📺 WiTV 管理',
        'header.subtitle': 'Android TV M3U 播放器',
        'lang.toggle': 'English',
        'source.add.title': '添加播放源',
        'source.name': '名称',
        'source.name.placeholder': '如：IPTV 直播源',
        'source.url': 'M3U 地址',
        'source.add.button': '添加',
        'source.list.title': '播放源列表',
        'source.empty': '暂无播放源',
        'source.active': '当前使用',
        'source.activate': '切换',
        'source.reload': '刷新',
        'source.channels': '频道',
        'source.delete': '删除',
        'source.added.active': '播放源已添加，电视加载完频道后会自动开始播放',
        'source.added.inactive': '播放源已添加。要在电视上播放它，点它旁边的“切换”',
        'source.add.failed': '添加失败：{msg}',
        'source.activated': '已切换播放源',
        'source.activate.failed': '切换失败',
        'source.delete.confirm': '确定删除此播放源？',
        'source.deleted': '已删除',
        'source.delete.failed': '删除失败',
        'source.reloading': '正在重新加载…',
        'source.reload.failed': '刷新失败',
        'favorites.title': '⭐ 我的收藏',
        'favorites.count': '{n} 个',
        'favorite.add': '收藏',
        'favorite.remove': '取消收藏',
        'favorite.added': '已添加到收藏',
        'favorite.removed': '已取消收藏',
        'favorite.failed': '操作失败',
        'channels.title': '频道列表',
        'channels.loading': '正在加载频道列表…',
        'channels.load.failed': '频道加载失败',
        'channels.group.other': '其他',
        'channels.count': '共 {n} 个频道',
        'settings.title': '设置',
        'settings.epg': 'EPG 节目预告地址 (XMLTV)',
        'settings.epg.useCustom': '使用自定义地址',
        'settings.epg.m3u': '直播源内（M3U 的 x-tvg-url）',
        'settings.epg.m3u.none': '这个直播源没有声明 EPG 地址',
        'settings.epg.custom': '自定义',
        'settings.epg.inUse': '使用中',
        'settings.epg.none': '两个地址都是空的，暂时没有节目预告',
        'settings.epg.noSource': '还没有启用的直播源，先在上面添加一个',
        'settings.epg.fallbackM3u': '自定义地址是空的，暂时用直播源内的地址',
        'settings.epg.fallbackCustom': '直播源没有声明 EPG 地址，暂时用自定义地址',
        'settings.epg.customMissing': '先填写自定义 EPG 地址',
        'settings.saved.epgRefreshing': '设置已保存，EPG 正在按新地址后台刷新',
        'settings.save': '保存',
        'settings.reloadEpg': '刷新EPG',
        'settings.udpxy': '组播转单播代理 (udpxy)',
        'settings.udpxy.preview': 'udp:// 和 rtp:// 频道将改走：rtp://239.1.1.1:1234 → {base}/rtp/239.1.1.1:1234',
        'settings.udpxy.none': '未配置，udp:// 和 rtp:// 频道直接收组播（需设备所在网络能收到运营商组播）',
        'settings.saved': '设置已保存',
        'settings.save.failed': '保存失败',
        'epg.refreshing': 'EPG 正在刷新…',
        'epg.refresh.failed': 'EPG 刷新失败',
        'epg.url.missing': '请先设置 EPG 地址',
        'epg.loading': '正在加载节目信息…',
        'epg.empty': '暂无节目信息',
        'epg.load.failed': '节目信息加载失败',
        'epg.now': '当前播出',
        'epg.next': '即将播出',
        'epg.later': '后续',
        'epg.footer.capped': '已显示 {n} 条未结束节目（已达本页上限，数据库中可能还有更多）',
        'epg.footer.total': '共 {n} 条未结束节目'
    },
    en: {
        'page.title': 'WiTV Admin',
        'header.title': '📺 WiTV Admin',
        'header.subtitle': 'M3U player for Android TV',
        'lang.toggle': '中文',
        'source.add.title': 'Add playlist',
        'source.name': 'Name',
        'source.name.placeholder': 'e.g. IPTV live',
        'source.url': 'M3U URL',
        'source.add.button': 'Add',
        'source.list.title': 'Playlists',
        'source.empty': 'No playlists yet',
        'source.active': 'In use',
        'source.activate': 'Use',
        'source.reload': 'Refresh',
        'source.channels': 'Channels',
        'source.delete': 'Delete',
        'source.added.active': 'Playlist added. The TV will start playing once channels are loaded',
        'source.added.inactive': 'Playlist added. To play it on the TV, click "Use" next to it',
        'source.add.failed': 'Failed to add: {msg}',
        'source.activated': 'Playlist switched',
        'source.activate.failed': 'Failed to switch',
        'source.delete.confirm': 'Delete this playlist?',
        'source.deleted': 'Deleted',
        'source.delete.failed': 'Failed to delete',
        'source.reloading': 'Reloading…',
        'source.reload.failed': 'Failed to refresh',
        'favorites.title': '⭐ Favorites',
        'favorites.count': '{n}',
        'favorite.add': 'Add to favorites',
        'favorite.remove': 'Remove from favorites',
        'favorite.added': 'Added to favorites',
        'favorite.removed': 'Removed from favorites',
        'favorite.failed': 'Operation failed',
        'channels.title': 'Channels',
        'channels.loading': 'Loading channels…',
        'channels.load.failed': 'Failed to load channels',
        'channels.group.other': 'Other',
        'channels.count': '{n} channels',
        'settings.title': 'Settings',
        'settings.epg': 'EPG URL (XMLTV)',
        'settings.epg.useCustom': 'Use custom URL',
        'settings.epg.m3u': 'From playlist (M3U x-tvg-url)',
        'settings.epg.m3u.none': 'This playlist does not declare an EPG URL',
        'settings.epg.custom': 'Custom',
        'settings.epg.inUse': 'In use',
        'settings.epg.none': 'Both URLs are empty, so there is no program guide yet',
        'settings.epg.noSource': 'No active playlist yet. Add one above first',
        'settings.epg.fallbackM3u': 'The custom URL is empty, so the playlist URL is used for now',
        'settings.epg.fallbackCustom': 'The playlist declares no EPG URL, so the custom URL is used for now',
        'settings.epg.customMissing': 'Enter a custom EPG URL first',
        'settings.saved.epgRefreshing': 'Settings saved. Refreshing the EPG from the new URL in the background',
        'settings.save': 'Save',
        'settings.reloadEpg': 'Refresh EPG',
        'settings.udpxy': 'Multicast-to-unicast proxy (udpxy)',
        'settings.udpxy.preview': 'udp:// and rtp:// channels will be played via: rtp://239.1.1.1:1234 → {base}/rtp/239.1.1.1:1234',
        'settings.udpxy.none': 'Not set. udp:// and rtp:// channels receive multicast directly (your network must carry the ISP multicast)',
        'settings.saved': 'Settings saved',
        'settings.save.failed': 'Failed to save',
        'epg.refreshing': 'Refreshing EPG…',
        'epg.refresh.failed': 'Failed to refresh EPG',
        'epg.url.missing': 'Please set an EPG URL first',
        'epg.loading': 'Loading program guide…',
        'epg.empty': 'No program info',
        'epg.load.failed': 'Failed to load program guide',
        'epg.now': 'Now',
        'epg.next': 'Next',
        'epg.later': 'Later',
        'epg.footer.capped': 'Showing {n} upcoming programs (page limit reached; there may be more)',
        'epg.footer.total': '{n} upcoming programs'
    }
};

const LANG_STORAGE_KEY = 'witv_lang';

function detectLang() {
    try {
        const saved = localStorage.getItem(LANG_STORAGE_KEY);
        if (saved && I18N[saved]) return saved;
    } catch (e) {
        // 隐私模式等情况下 localStorage 不可用，退回浏览器语言
    }
    const nav = (navigator.languages && navigator.languages[0]) || navigator.language || '';
    return nav.toLowerCase().startsWith('zh') ? 'zh' : 'en';
}

let currentLang = detectLang();

/** 取文案；{name} 占位符用 params 替换 */
function t(key, params) {
    const dict = I18N[currentLang] || I18N.zh;
    let s = dict[key] != null ? dict[key] : (I18N.zh[key] != null ? I18N.zh[key] : key);
    if (params) {
        Object.keys(params).forEach(k => {
            s = s.split('{' + k + '}').join(String(params[k]));
        });
    }
    return s;
}

/** 填充带 data-i18n / data-i18n-placeholder 的静态元素 */
function applyI18n() {
    document.documentElement.lang = currentLang === 'zh' ? 'zh-CN' : 'en';
    document.title = t('page.title');
    document.querySelectorAll('[data-i18n]').forEach(el => {
        el.textContent = t(el.getAttribute('data-i18n'));
    });
    document.querySelectorAll('[data-i18n-placeholder]').forEach(el => {
        el.placeholder = t(el.getAttribute('data-i18n-placeholder'));
    });
}

function toggleLang() {
    const next = currentLang === 'zh' ? 'en' : 'zh';
    try {
        localStorage.setItem(LANG_STORAGE_KEY, next);
    } catch (e) {
        // 存不了也照样切换，只是刷新后不记住
    }
    currentLang = next;
    // 动态列表都是按当前语言渲染的，重新加载一次最简单可靠
    location.reload();
}

applyI18n();
