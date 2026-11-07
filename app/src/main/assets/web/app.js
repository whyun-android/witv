const API = '';
let favoriteIds = new Set();

document.getElementById('addSourceForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const name = document.getElementById('sourceName').value.trim();
    const url = document.getElementById('sourceUrl').value.trim();
    if (!url) return;

    try {
        const res = await fetch(`${API}/api/sources`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json; charset=utf-8' },
            body: JSON.stringify({ name: name || url, url })
        });
        if (!res.ok) throw new Error(await res.text());
        const added = await res.json().catch(() => ({}));
        showToast(added.isActive
            ? t('source.added.active')
            : t('source.added.inactive'), 'success');
        document.getElementById('sourceName').value = '';
        document.getElementById('sourceUrl').value = '';
        setTimeout(loadSources, 2000);
    } catch (err) {
        showToast(t('source.add.failed', { msg: err.message }), 'error');
    }
});

async function loadSources() {
    try {
        const res = await fetch(`${API}/api/sources`);
        const sources = await res.json();
        renderSources(sources);
    } catch (err) {
        console.error('Failed to load sources:', err);
    }
}

function renderSources(sources) {
    const container = document.getElementById('sourceList');
    if (!sources || sources.length === 0) {
        container.innerHTML = `<p class="empty-hint">${t('source.empty')}</p>`;
        document.getElementById('channelSection').style.display = 'none';
        return;
    }

    container.innerHTML = sources.map(s => `
        <div class="source-item ${s.isActive ? 'active' : ''}">
            <div class="source-info">
                <div class="source-name">${escapeHtml(s.name)}</div>
                <div class="source-url">${escapeHtml(s.url)}</div>
            </div>
            ${s.isActive ? `<span class="source-status status-active">${t('source.active')}</span>` : ''}
            <div class="source-actions">
                ${!s.isActive ? `<button class="btn btn-secondary btn-sm" onclick="activateSource(${s.id})">${t('source.activate')}</button>` : ''}
                <button class="btn btn-secondary btn-sm" onclick="reloadSource(${s.id})">${t('source.reload')}</button>
                <button class="btn btn-secondary btn-sm" onclick="viewChannels(${s.id})">${t('source.channels')}</button>
                <button class="btn btn-danger btn-sm" onclick="deleteSource(${s.id})">${t('source.delete')}</button>
            </div>
        </div>
    `).join('');

    const active = sources.find(s => s.isActive);
    if (active) {
        viewChannels(active.id);
    }
}

async function activateSource(id) {
    try {
        await fetch(`${API}/api/sources/${id}/activate`, { method: 'POST' });
        showToast(t('source.activated'), 'success');
        loadSources();
    } catch (err) {
        showToast(t('source.activate.failed'), 'error');
    }
}

async function deleteSource(id) {
    if (!confirm(t('source.delete.confirm'))) return;
    try {
        await fetch(`${API}/api/sources/${id}`, { method: 'DELETE' });
        showToast(t('source.deleted'), 'success');
        loadSources();
    } catch (err) {
        showToast(t('source.delete.failed'), 'error');
    }
}

async function reloadSource(id) {
    try {
        await fetch(`${API}/api/sources/${id}/reload`, { method: 'POST' });
        showToast(t('source.reloading'), 'success');
        setTimeout(() => viewChannels(id), 3000);
    } catch (err) {
        showToast(t('source.reload.failed'), 'error');
    }
}

async function viewChannels(sourceId) {
    const section = document.getElementById('channelSection');
    const container = document.getElementById('channelGroups');
    const countBadge = document.getElementById('channelCount');
    section.style.display = 'block';
    countBadge.textContent = '';
    container.innerHTML = `<div class="loading-state"><span class="spinner"></span>${t('channels.loading')}</div>`;
    try {
        const res = await fetch(`${API}/api/sources/${sourceId}/channels`);
        const data = await res.json();
        renderChannels(data);
    } catch (err) {
        container.innerHTML = `<p class="empty-hint">${t('channels.load.failed')}</p>`;
        console.error('Failed to load channels:', err);
    }
}

function renderChannels(data) {
    const section = document.getElementById('channelSection');
    const container = document.getElementById('channelGroups');
    const countBadge = document.getElementById('channelCount');

    if (!data || !data.groups || data.groups.length === 0) {
        section.style.display = 'none';
        return;
    }

    let totalChannels = 0;
    let html = '';

    data.groups.forEach(group => {
        const channels = data.channels[group] || [];
        totalChannels += channels.length;
        const displayGroup = group || t('channels.group.other');

        html += `
            <div>
                <div class="group-header" onclick="this.nextElementSibling.style.display = this.nextElementSibling.style.display === 'none' ? 'grid' : 'none'">
                    ${escapeHtml(displayGroup)} (${channels.length})
                </div>
                <div class="group-channels">
                    ${channels.map(ch => `
                        <div class="channel-item" onclick="showEpg(${ch.id}, '${escapeHtml(ch.displayName).replace(/'/g, "\\'")}')">
                            ${ch.logoUrl ? `<img src="${escapeHtml(ch.logoUrl)}" alt="" onerror="this.style.display='none'">` : ''}
                            <span>${escapeHtml(ch.displayName)}</span>
                            <button class="btn-fav ${favoriteIds.has(ch.id) ? 'is-fav' : ''}"
                                    onclick="event.stopPropagation();toggleFavorite(${ch.id}, this)" title="${favoriteIds.has(ch.id) ? t('favorite.remove') : t('favorite.add')}">
                                ${favoriteIds.has(ch.id) ? '★' : '☆'}
                            </button>
                        </div>
                    `).join('')}
                </div>
            </div>
        `;
    });

    countBadge.textContent = t('channels.count', { n: totalChannels });
    container.innerHTML = html;
    section.style.display = 'block';
}

async function loadFavorites() {
    try {
        const res = await fetch(`${API}/api/favorites`);
        const data = await res.json();
        favoriteIds = new Set(data.ids || []);
        renderFavorites(data.channels || []);
    } catch (err) {
        console.error('Failed to load favorites:', err);
    }
}

function renderFavorites(channels) {
    const section = document.getElementById('favoriteSection');
    const container = document.getElementById('favoriteList');
    const countBadge = document.getElementById('favoriteCount');

    if (!channels || channels.length === 0) {
        section.style.display = 'none';
        return;
    }

    countBadge.textContent = t('favorites.count', { n: channels.length });
    container.innerHTML = channels.map(ch => `
        <div class="channel-item">
            ${ch.logoUrl ? `<img src="${escapeHtml(ch.logoUrl)}" alt="" onerror="this.style.display='none'">` : ''}
            <span>${escapeHtml(ch.displayName)}</span>
            <button class="btn-fav is-fav" onclick="toggleFavorite(${ch.id}, this)" title="${t('favorite.remove')}">★</button>
        </div>
    `).join('');
    section.style.display = 'block';
}

async function toggleFavorite(channelId, btn) {
    const isFav = favoriteIds.has(channelId);
    try {
        if (isFav) {
            await fetch(`${API}/api/favorites/${channelId}`, { method: 'DELETE' });
            favoriteIds.delete(channelId);
            showToast(t('favorite.removed'), 'success');
        } else {
            await fetch(`${API}/api/favorites/${channelId}`, { method: 'POST' });
            favoriteIds.add(channelId);
            showToast(t('favorite.added'), 'success');
        }
        if (btn) {
            btn.className = `btn-fav ${favoriteIds.has(channelId) ? 'is-fav' : ''}`;
            btn.textContent = favoriteIds.has(channelId) ? '★' : '☆';
            btn.title = favoriteIds.has(channelId) ? t('favorite.remove') : t('favorite.add');
        }
        loadFavorites();
    } catch (err) {
        showToast(t('favorite.failed'), 'error');
    }
}

async function loadSettings() {
    try {
        const res = await fetch(`${API}/api/settings`);
        const settings = await res.json();
        renderEpgSettings(settings);
        document.getElementById('udpxyProxyBase').value = settings.udpxyProxyBase || '';
        renderUdpxyPreview(settings.udpxyProxyBase || '');
    } catch (err) {
        console.error('Failed to load settings:', err);
    }
}

// 用一条示例频道展示改写效果，比单看前缀直观
function renderUdpxyPreview(base) {
    const el = document.getElementById('udpxyPreview');
    if (!el) return;
    el.textContent = base
        ? t('settings.udpxy.preview', { base })
        : t('settings.udpxy.none');
}

// 最近一次从后端拿到的 EPG 设置，切换开关时用来重算「使用中」标记
let epgSettings = { epgUrl: '', m3uEpgUrl: '', customEpgUrl: '', useCustomEpg: false, hasActiveSource: false };

// 两个候选地址并列，实际生效的那个标「使用中」。开关选中的一侧为空时后端会退回另一侧，这里照实提示
function renderEpgSettings(s) {
    epgSettings = Object.assign(epgSettings, s || {});
    const { epgUrl, m3uEpgUrl, customEpgUrl, useCustomEpg, hasActiveSource } = epgSettings;
    const m3uEl = document.getElementById('m3uEpgUrl');
    m3uEl.textContent = m3uEpgUrl || t('settings.epg.m3u.none');
    m3uEl.classList.toggle('is-empty', !m3uEpgUrl);
    const customInput = document.getElementById('customEpgUrl');
    if (document.activeElement !== customInput) {
        customInput.value = customEpgUrl || '';
    }
    document.getElementById('useCustomEpg').checked = !!useCustomEpg;

    const usingCustom = !!epgUrl && (useCustomEpg ? epgUrl === customEpgUrl : epgUrl !== m3uEpgUrl);
    const usingM3u = !!epgUrl && !usingCustom;
    document.getElementById('epgOptionCustom').classList.toggle('in-use', usingCustom);
    document.getElementById('epgOptionM3u').classList.toggle('in-use', usingM3u);

    const hint = document.getElementById('epgEffectiveHint');
    if (!hasActiveSource) {
        hint.textContent = t('settings.epg.noSource');
    } else if (!epgUrl) {
        hint.textContent = t('settings.epg.none');
    } else if (useCustomEpg && !usingCustom) {
        hint.textContent = t('settings.epg.fallbackM3u');
    } else if (!useCustomEpg && usingCustom) {
        hint.textContent = t('settings.epg.fallbackCustom');
    } else {
        hint.textContent = '';
    }
}

// 开关切了就立即生效，不用再点保存
function onEpgSwitchChange() {
    const toggle = document.getElementById('useCustomEpg');
    const customInput = document.getElementById('customEpgUrl');
    if (toggle.checked && !customInput.value.trim()) {
        toggle.checked = false;
        customInput.focus();
        showToast(t('settings.epg.customMissing'), 'error');
        return;
    }
    saveSettings();
}

async function saveSettings() {
    const customEpgUrl = document.getElementById('customEpgUrl').value.trim();
    const useCustomEpg = document.getElementById('useCustomEpg').checked;
    const udpxyProxyBase = document.getElementById('udpxyProxyBase').value.trim();
    try {
        const res = await fetch(`${API}/api/settings`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json; charset=utf-8' },
            body: JSON.stringify({ customEpgUrl, useCustomEpg, udpxyProxyBase })
        });
        const data = await res.json();
        renderEpgSettings({ epgUrl: data.epgUrl || '', customEpgUrl, useCustomEpg });
        // 后端会规范化（补 scheme、去掉末尾 / 和 /udp /rtp），回填实际生效的值
        const normalized = data.udpxyProxyBase || '';
        document.getElementById('udpxyProxyBase').value = normalized;
        renderUdpxyPreview(normalized);
        showToast(data.epgRefreshing ? t('settings.saved.epgRefreshing') : t('settings.saved'), 'success');
    } catch (err) {
        showToast(t('settings.save.failed'), 'error');
    }
}

async function reloadEpg() {
    try {
        const res = await fetch(`${API}/api/epg/reload`, { method: 'POST' });
        const data = await res.json();
        if (data.error) {
            // 后端错误信息是英文，已知的那条换成当前语言的文案
            showToast(data.error === 'No EPG URL configured' ? t('epg.url.missing') : data.error, 'error');
        } else {
            showToast(t('epg.refreshing'), 'success');
        }
    } catch (err) {
        showToast(t('epg.refresh.failed'), 'error');
    }
}

function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
              .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function showToast(msg, type) {
    const toast = document.getElementById('toast');
    toast.textContent = msg;
    toast.className = 'toast show ' + type;
    setTimeout(() => { toast.className = 'toast'; }, 3000);
}

async function loadVersion() {
    try {
        const res = await fetch(`${API}/api/version`);
        const data = await res.json();
        document.getElementById('appVersion').textContent = 'v' + (data.version || '');
    } catch (err) {
        console.error('Failed to load version:', err);
    }
}

const EPG_LIST_LIMIT = 48;

async function showEpg(channelId, channelName) {
    const modal = document.getElementById('epgModal');
    const title = document.getElementById('epgModalTitle');
    const body = document.getElementById('epgModalBody');

    title.textContent = channelName;
    body.innerHTML = `<div class="loading-state"><span class="spinner"></span>${t('epg.loading')}</div>`;
    modal.classList.add('show');

    try {
        const res = await fetch(`${API}/api/epg/channel/${channelId}?limit=${EPG_LIST_LIMIT}`);
        const data = await res.json();
        const programs = data.programs || [];
        if (programs.length === 0) {
            body.innerHTML = `<p class="epg-empty">${t('epg.empty')}</p>`;
            return;
        }
        const fmt = ts => {
            const d = new Date(ts);
            return d.getHours().toString().padStart(2, '0') + ':' + d.getMinutes().toString().padStart(2, '0');
        };
        const now = Date.now();
        const curIdx = programs.findIndex(p => p.startTime <= now && p.endTime > now);
        const nextIdx = curIdx === -1 ? 0 : curIdx + 1;

        function epgLabel(i) {
            if (i === curIdx) return t('epg.now');
            if (i === nextIdx) return t('epg.next');
            return t('epg.later');
        }

        const rows = programs.map((p, i) => {
            const isCurrent = i === curIdx;
            const desc = p.description && String(p.description).trim();
            return `
            <div class="epg-item ${isCurrent ? 'epg-current' : ''}">
                <span class="epg-label">${epgLabel(i)}</span>
                <span class="epg-title">${escapeHtml(p.title)}</span>
                <span class="epg-time">${fmt(p.startTime)} - ${fmt(p.endTime)}</span>
                ${desc ? `<p class="epg-desc">${escapeHtml(desc)}</p>` : ''}
            </div>`;
        }).join('');

        const cap = data.limit != null ? data.limit : EPG_LIST_LIMIT;
        let footerText;
        if (programs.length >= cap) {
            footerText = t('epg.footer.capped', { n: cap });
        } else {
            footerText = t('epg.footer.total', { n: programs.length });
        }
        const footer = `<p class="epg-footer">${footerText}</p>`;

        body.innerHTML = rows + footer;
    } catch (err) {
        body.innerHTML = `<p class="epg-empty">${t('epg.load.failed')}</p>`;
    }
}

function closeEpgModal(event) {
    if (event && event.target !== event.currentTarget) return;
    document.getElementById('epgModal').classList.remove('show');
}

// Initialize
loadVersion();
loadFavorites().then(() => loadSources());
loadSettings();
