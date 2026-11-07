// WiTV 宣传站：首屏电视演示 + 中英切换。没有依赖，直接以静态文件部署。
(function () {
  'use strict';

  // ---------- 文案 ----------
  var I18N = {
    zh: {
      'nav.setup': '三步装好', 'nav.features': '功能', 'nav.remote': '遥控器', 'nav.download': '下载',
      'tv.hint': '↑↓ 换台　←→ 切换分组　确认 播放',
      'hero.title': '打开电视，就在播。',
      'hero.lead': 'WiTV 是给 Android TV 和电视盒子用的开源直播播放器。它播放你自己的 M3U 直播源：开机就在播，用遥控器换台，设置在手机上完成。',
      'hero.note': '上面这台电视可以直接操作：点一下画面，再按方向键。',
      'cta.download': '下载 APK', 'cta.source': '查看源代码', 'cta.guide': '阅读使用手册',
      'theme.broadcast': '电视台包装', 'theme.standard': '标准',
      'who.title': '沙发上的人什么都不用设置',
      'who.viewer.title': '看电视的人',
      'who.viewer.body': '家里的老人和孩子只需要方向键、确认和返回。开机直接进直播，换台、找分组、按数字键跳台，字号按三米外能看清来定。',
      'who.admin.title': '管设备的人',
      'who.admin.body': '直播源、节目单、组播代理都在手机或电脑浏览器里配置。电视屏幕上显示二维码和局域网地址，不用拿遥控器打字。',
      'setup.title': '三步装好',
      'setup.1.title': '把 APK 装到盒子上',
      'setup.1.body': '支持 Android 6.0 及以上的电视和盒子，在斐讯 N1（Android 7.1）上开发和测试。',
      'setup.2.title': '用手机扫电视上的二维码',
      'setup.2.body': '第一次打开时，电视上会显示管理页的二维码和地址（端口 9979）。手机和盒子在同一个局域网里就能打开。',
      'setup.3.title': '添加你的 M3U 地址',
      'setup.3.body': '在管理页里填上直播源地址，电视那边会自动加载频道并开始播放。',
      'features.title': '它能做什么',
      'f.failover.k': '源不稳', 'f.failover.t': '一个频道，多个播放地址',
      'f.failover.d': '同名频道的地址自动合并。播放失败或超时会按顺序换下一个，卡住了会自己重连，并在屏幕上告诉你发生了什么。',
      'f.menu.k': '换台', 'f.menu.t': '分组、频道、今日节目单三栏菜单',
      'f.menu.d': '为遥控器设计的三栏菜单，当前节目原位高亮。上下键直接换台，数字键跳到指定频道，常看的频道可以收藏。',
      'f.epg.k': '节目单', 'f.epg.t': 'XMLTV 节目预告',
      'f.epg.d': '可以用直播源里自带的 EPG 地址，也可以换成自己的，在管理页里一个开关切换，节目单会自动刷新。',
      'f.multicast.k': '组播', 'f.multicast.t': 'UDP / RTP 组播直连或走 udpxy',
      'f.multicast.d': '支持运营商 IPTV 组播频道，带 RTP 剥头与乱序重排；收不到组播时可以配置 udpxy 转成 HTTP 单播。',
      'f.decoder.k': '老盒子', 'f.decoder.t': '硬解优先，FFmpeg 兜底',
      'f.decoder.d': '内置 FFmpeg 软解，补上盒子硬解常缺的 MPEG-2 视频与 MP2 / AC3 音频。可以选择硬解优先、软解优先或仅硬解。',
      'f.launcher.k': '开机', 'f.launcher.t': '可以当桌面用',
      'f.launcher.d': '设为默认桌面后，开机、按主页键都直接进直播。返回键里的「切换应用」随时能打开其他应用或换回原来的桌面。',
      'f.look.k': '外观', 'f.look.t': '两套主题，中英文界面',
      'f.look.d': '「标准」是深色面板加蓝色焦点，「电视台包装」是演播室深蓝加白色字幕条。首屏的电视可以直接切换看看。',
      'remote.title': '遥控器怎么按',
      'k.ok': '确认', 'k.ok.d': '打开频道菜单；停留一会儿没有操作会自动收起',
      'k.updown': '上 / 下', 'k.updown.d': '上一个 / 下一个频道（方向可以在设置里反过来）',
      'k.num': '数字键', 'k.num.d': '输入频道号直接跳转',
      'k.info': '信息键', 'k.info.d': '显示 / 隐藏节目与信号信息',
      'k.menu': '菜单键', 'k.menu.d': '打开设置',
      'k.back': '返回', 'k.back.d': '关闭菜单；在播放时按下可以去设置、切换应用或退出',
      'download.title': '下载',
      'download.body': '从 GitHub Releases 下载最新的 APK，用 U 盘、adb 或盒子自带的文件管理器安装。WiTV 使用 MIT 协议开源，没有账号，也不联网上报任何数据。',
      'download.disclaimer': 'WiTV 本身不提供任何频道或节目内容，需要你自己添加合法的直播源。本页演示画面中的频道和节目均为示意。',
      'footer.source': '源代码', 'footer.issues': '反馈问题', 'footer.license': 'MIT 协议'
    },
    en: {
      'nav.setup': 'Setup', 'nav.features': 'Features', 'nav.remote': 'Remote', 'nav.download': 'Download',
      'tv.hint': '↑↓ change channel   ←→ switch group   OK play',
      'hero.title': 'Turn on the TV. It’s already playing.',
      'hero.lead': 'WiTV is an open-source live TV player for Android TV and TV boxes. It plays your own M3U playlists: it starts playing at boot, you change channels with the remote, and you set it up from your phone.',
      'hero.note': 'The TV above works: click the screen, then use the arrow keys.',
      'cta.download': 'Download APK', 'cta.source': 'View source code', 'cta.guide': 'Read the user guide',
      'theme.broadcast': 'Broadcast', 'theme.standard': 'Standard',
      'who.title': 'Nobody on the sofa has to configure anything',
      'who.viewer.title': 'The people watching',
      'who.viewer.body': 'Grandparents and kids only need the arrows, OK and Back. It boots straight into live TV; change channels, browse groups or type a channel number. Text is sized to be read from three metres away.',
      'who.admin.title': 'The person who sets it up',
      'who.admin.body': 'Playlists, the programme guide and the multicast proxy are configured from a phone or desktop browser. The TV shows a QR code and the LAN address, so there is no typing with the remote.',
      'setup.title': 'Set up in three steps',
      'setup.1.title': 'Install the APK on the box',
      'setup.1.body': 'Runs on TVs and boxes with Android 6.0 or later. Developed and tested on a Phicomm N1 (Android 7.1).',
      'setup.2.title': 'Scan the QR code on the TV',
      'setup.2.body': 'On first launch the TV shows a QR code and the address of the admin page (port 9979). Open it on any phone on the same network.',
      'setup.3.title': 'Add your M3U URL',
      'setup.3.body': 'Paste your playlist URL into the admin page. The TV loads the channels and starts playing.',
      'features.title': 'What it does',
      'f.failover.k': 'Flaky sources', 'f.failover.t': 'Several stream URLs per channel',
      'f.failover.d': 'Streams with the same channel name are merged. When one fails or times out, the next one is tried in order; stalls reconnect on their own, and the screen tells you what happened.',
      'f.menu.k': 'Zapping', 'f.menu.t': 'Group, channel and today’s guide in one menu',
      'f.menu.d': 'A three-column menu built for the remote, with the current programme highlighted in place. Up and down change channels, number keys jump straight to a channel, and favourites keep the ones you watch most.',
      'f.epg.k': 'Guide', 'f.epg.t': 'XMLTV programme guide',
      'f.epg.d': 'Use the EPG URL declared in your playlist or your own. One switch on the admin page chooses between them, and the guide refreshes automatically.',
      'f.multicast.k': 'Multicast', 'f.multicast.t': 'UDP / RTP multicast, direct or through udpxy',
      'f.multicast.d': 'Plays ISP IPTV multicast channels, with RTP header stripping and reordering. If your network can’t receive multicast, point it at a udpxy proxy to get HTTP unicast instead.',
      'f.decoder.k': 'Old boxes', 'f.decoder.t': 'Hardware first, FFmpeg as backup',
      'f.decoder.d': 'Bundled FFmpeg decoding covers what box hardware often can’t: MPEG-2 video and MP2 / AC3 audio. Choose hardware first, software first or hardware only.',
      'f.launcher.k': 'Boot', 'f.launcher.t': 'Works as the home screen',
      'f.launcher.d': 'Set it as the default launcher and both boot and the Home key go straight to live TV. “Switch app” in the Back menu opens other apps or your old launcher at any time.',
      'f.look.k': 'Look', 'f.look.t': 'Two themes, Chinese and English UI',
      'f.look.d': '“Standard” is a dark panel with a blue focus; “Broadcast” is studio navy with a white caption bar. Try both on the TV at the top of this page.',
      'remote.title': 'Using the remote',
      'k.ok': 'OK', 'k.ok.d': 'Open the channel menu; it closes by itself after a short idle',
      'k.updown': 'Up / Down', 'k.updown.d': 'Previous / next channel (the direction can be reversed in settings)',
      'k.num': 'Number keys', 'k.num.d': 'Jump to a channel number',
      'k.info': 'Info', 'k.info.d': 'Show / hide programme and signal info',
      'k.menu': 'Menu', 'k.menu.d': 'Open settings',
      'k.back': 'Back', 'k.back.d': 'Close menus; while watching, opens settings, app switching or exit',
      'download.title': 'Download',
      'download.body': 'Get the latest APK from GitHub Releases and install it with a USB stick, adb or the box’s file manager. WiTV is open source under the MIT license, has no accounts, and sends no data anywhere.',
      'download.disclaimer': 'WiTV does not provide any channels or programmes; you add your own legal playlists. Channels and programmes in the demo on this page are made up.',
      'footer.source': 'Source code', 'footer.issues': 'Report an issue', 'footer.license': 'MIT license'
    }
  };

  // ---------- 演示数据：频道与节目都是示意 ----------
  var DEMO = {
    zh: {
      groups: ['全部频道', '收藏', '新闻', '体育', '影视', '少儿'],
      channels: [
        { no: 1, name: '城市新闻', group: 2, epg: [['18:00', '城市晚报'], ['19:00', '新闻联线'], ['19:35', '天气与交通'], ['20:00', '一周调查'], ['21:00', '夜间新闻']] },
        { no: 2, name: '财经资讯', group: 2, epg: [['18:30', '收盘点评'], ['19:30', '公司与市场'], ['20:30', '财经夜读'], ['21:30', '环球市场']] },
        { no: 3, name: '体育赛事', group: 3, epg: [['18:00', '赛前直击'], ['19:30', '篮球联赛 第 12 轮'], ['21:40', '赛事集锦'], ['22:30', '体坛快讯']] },
        { no: 5, name: '户外运动', group: 3, epg: [['18:20', '山野徒步'], ['19:30', '骑行中国'], ['20:30', '冲浪季'], ['21:30', '极限挑战']] },
        { no: 6, name: '经典电影', group: 4, epg: [['17:50', '午后影院'], ['19:40', '故事片：远方的家'], ['21:50', '影人访谈'], ['22:30', '深夜剧场']] },
        { no: 7, name: '热播剧场', group: 4, epg: [['18:10', '家常菜（23）'], ['19:30', '晚风（11）'], ['20:20', '晚风（12）'], ['21:10', '剧场花絮']] },
        { no: 8, name: '纪录片', group: 4, epg: [['18:00', '海岸线'], ['19:00', '自然的力量'], ['20:00', '古城往事'], ['21:00', '星空之下']] },
        { no: 9, name: '少儿动画', group: 5, epg: [['18:00', '小熊的森林'], ['18:40', '奇妙科学课'], ['19:30', '晚安故事'], ['20:00', '动画剧场']] }
      ],
      sub: '正在播：', live: '直播中', badge: '正在播出', today: '今天'
    },
    en: {
      groups: ['All channels', 'Favourites', 'News', 'Sports', 'Movies', 'Kids'],
      channels: [
        { no: 1, name: 'City News', group: 2, epg: [['18:00', 'Evening Edition'], ['19:00', 'News Hour'], ['19:35', 'Weather & Traffic'], ['20:00', 'The Week'], ['21:00', 'Late News']] },
        { no: 2, name: 'Market Watch', group: 2, epg: [['18:30', 'Closing Bell'], ['19:30', 'Companies'], ['20:30', 'Money Talk'], ['21:30', 'Global Markets']] },
        { no: 3, name: 'Sports Live', group: 3, epg: [['18:00', 'Pre-game'], ['19:30', 'Basketball League R12'], ['21:40', 'Highlights'], ['22:30', 'Sports Desk']] },
        { no: 5, name: 'Outdoors', group: 3, epg: [['18:20', 'Trail Days'], ['19:30', 'On Two Wheels'], ['20:30', 'Surf Season'], ['21:30', 'Edge']] },
        { no: 6, name: 'Classic Movies', group: 4, epg: [['17:50', 'Matinee'], ['19:40', 'Feature: Far Home'], ['21:50', 'Director’s Chair'], ['22:30', 'Late Show']] },
        { no: 7, name: 'Drama', group: 4, epg: [['18:10', 'Home Cooking (23)'], ['19:30', 'Night Wind (11)'], ['20:20', 'Night Wind (12)'], ['21:10', 'Behind the Scenes']] },
        { no: 8, name: 'Documentary', group: 4, epg: [['18:00', 'Coastline'], ['19:00', 'Forces of Nature'], ['20:00', 'Old Towns'], ['21:00', 'Under the Stars']] },
        { no: 9, name: 'Kids', group: 5, epg: [['18:00', 'Bear Forest'], ['18:40', 'Science Club'], ['19:30', 'Bedtime Stories'], ['20:00', 'Cartoon Time']] }
      ],
      sub: 'Now: ', live: 'LIVE', badge: 'On now', today: 'Today'
    }
  };
  // 每个频道「当前节目」的下标与进度，固定写死，演示画面不随真实时间变化
  var CURRENT = [1, 1, 1, 1, 1, 1, 1, 1];
  var PROGRESS = [.42, .25, .63, .3, .55, .7, .36, .5];

  var lang = 'zh';
  try {
    var saved = localStorage.getItem('witv-site-lang');
    if (saved === 'zh' || saved === 'en') {
      lang = saved;
    } else if (!/^zh/i.test(navigator.language || '')) {
      lang = 'en';
    }
  } catch (e) { /* 读不到存储就按中文 */ }

  var focusIndex = 2;     // 光标所在频道
  var playingIndex = 2;   // 正在播放的频道
  var groupIndex = 0;

  var $ = function (id) { return document.getElementById(id); };

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  function visibleChannels() {
    var data = DEMO[lang];
    if (groupIndex < 2) return data.channels.map(function (c, i) { return i; });
    return data.channels.map(function (c, i) { return i; }).filter(function (i) { return data.channels[i].group === groupIndex; });
  }

  function render() {
    var data = DEMO[lang];
    var groups = $('tvGroups');
    groups.textContent = '';
    data.groups.forEach(function (g, i) {
      groups.appendChild(el('div', 'g' + (i === groupIndex ? ' sel' : ''), g));
    });

    var list = $('tvList');
    list.textContent = '';
    var idx = visibleChannels();
    if (idx.indexOf(focusIndex) < 0) focusIndex = idx[0];
    idx.forEach(function (i) {
      var c = data.channels[i];
      var row = el('div', 'row' + (i === focusIndex ? ' focus' : '') + (i === playingIndex ? ' playing' : ''));
      row.appendChild(el('span', 'no', String(c.no)));
      var body = el('div', 'body');
      body.appendChild(el('div', 'name', c.name));
      body.appendChild(el('div', 'sub', data.sub + c.epg[CURRENT[i]][1]));
      row.appendChild(body);
      if (i === playingIndex) row.appendChild(el('span', 'live', data.live));
      list.appendChild(row);
    });

    var ch = data.channels[focusIndex];
    $('tvEpgName').textContent = ch.no + '  ' + ch.name;
    $('tvEpgDate').textContent = data.today;
    var epg = $('tvEpgList');
    epg.textContent = '';
    ch.epg.forEach(function (p, j) {
      var cur = j === CURRENT[focusIndex];
      var row = el('div', 'p' + (j < CURRENT[focusIndex] ? ' past' : '') + (cur ? ' cur' : ''));
      row.appendChild(el('span', 't', p[0]));
      row.appendChild(el('span', 'title', p[1]));
      if (cur) {
        var meta = el('span', 'meta');
        meta.appendChild(el('span', 'k', data.badge));
        var bar = el('span', 'bar');
        var fill = el('i');
        fill.style.width = Math.round(PROGRESS[focusIndex] * 100) + '%';
        bar.appendChild(fill);
        meta.appendChild(bar);
        row.appendChild(meta);
      }
      epg.appendChild(row);
    });
  }

  function move(key) {
    var idx = visibleChannels();
    var pos = idx.indexOf(focusIndex);
    if (key === 'up') focusIndex = idx[(pos - 1 + idx.length) % idx.length];
    else if (key === 'down') focusIndex = idx[(pos + 1) % idx.length];
    else if (key === 'left' || key === 'right') {
      var n = DEMO[lang].groups.length;
      groupIndex = (groupIndex + (key === 'left' ? n - 1 : 1)) % n;
      var next = visibleChannels();
      if (next.indexOf(focusIndex) < 0) focusIndex = next[0];
    } else if (key === 'ok') {
      playingIndex = focusIndex;
    }
    render();
  }

  // 画面按 1280×720 排版，按容器宽度整体缩放
  function fit() {
    var box = $('tvStageBox');
    var stage = $('tvStage');
    stage.style.transform = 'scale(' + (box.clientWidth / 1280) + ')';
  }

  function applyLang() {
    var dict = I18N[lang];
    document.documentElement.lang = lang === 'zh' ? 'zh-CN' : 'en';
    document.querySelectorAll('[data-i18n]').forEach(function (n) {
      var v = dict[n.getAttribute('data-i18n')];
      if (v != null) n.textContent = v;
    });
    document.title = lang === 'zh' ? 'WiTV · 给电视盒子用的开源直播播放器' : 'WiTV · Open-source live TV player for TV boxes';
    $('langBtn').textContent = lang === 'zh' ? 'EN' : '中文';
    $('langBtn').setAttribute('aria-label', lang === 'zh' ? 'Switch to English' : '切换到中文');
    render();
  }

  function setTheme(theme) {
    $('tv').setAttribute('data-theme', theme);
    document.querySelectorAll('.theme-switch button').forEach(function (b) {
      b.setAttribute('aria-checked', String(b.getAttribute('data-theme') === theme));
    });
  }

  var KEYMAP = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Enter: 'ok', ' ': 'ok' };

  document.addEventListener('DOMContentLoaded', function () {
    var tv = $('tv');
    tv.addEventListener('keydown', function (e) {
      var k = KEYMAP[e.key];
      if (!k) return;
      e.preventDefault();
      move(k);
    });
    tv.addEventListener('click', function () { tv.focus(); });
    document.querySelectorAll('.remote .key').forEach(function (b) {
      b.addEventListener('click', function () { move(b.getAttribute('data-key')); });
    });
    document.querySelectorAll('.theme-switch button').forEach(function (b) {
      b.addEventListener('click', function () { setTheme(b.getAttribute('data-theme')); });
    });
    $('langBtn').addEventListener('click', function () {
      lang = lang === 'zh' ? 'en' : 'zh';
      try { localStorage.setItem('witv-site-lang', lang); } catch (e) { /* 存不了就只切这一次 */ }
      applyLang();
    });

    applyLang();
    fit();
    if (window.ResizeObserver) new ResizeObserver(fit).observe($('tvStageBox'));
    else window.addEventListener('resize', fit);
  });
})();
