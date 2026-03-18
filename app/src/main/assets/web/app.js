const API = '';

document.getElementById('addSourceForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const name = document.getElementById('sourceName').value.trim();
    const url = document.getElementById('sourceUrl').value.trim();
    if (!url) return;

    try {
        const res = await fetch(`${API}/api/sources`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: name || url, url })
        });
        if (!res.ok) throw new Error(await res.text());
        showToast('播放源添加成功，正在加载频道…', 'success');
        document.getElementById('sourceName').value = '';
        document.getElementById('sourceUrl').value = '';
        setTimeout(loadSources, 2000);
    } catch (err) {
        showToast('添加失败: ' + err.message, 'error');
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
        container.innerHTML = '<p class="empty-hint">暂无播放源</p>';
        document.getElementById('channelSection').style.display = 'none';
        return;
    }

    container.innerHTML = sources.map(s => `
        <div class="source-item ${s.isActive ? 'active' : ''}">
            <div class="source-info">
                <div class="source-name">${escapeHtml(s.name)}</div>
                <div class="source-url">${escapeHtml(s.url)}</div>
            </div>
            ${s.isActive ? '<span class="source-status status-active">当前使用</span>' : ''}
            <div class="source-actions">
                ${!s.isActive ? `<button class="btn btn-secondary btn-sm" onclick="activateSource(${s.id})">切换</button>` : ''}
                <button class="btn btn-secondary btn-sm" onclick="reloadSource(${s.id})">刷新</button>
                <button class="btn btn-secondary btn-sm" onclick="viewChannels(${s.id})">频道</button>
                <button class="btn btn-danger btn-sm" onclick="deleteSource(${s.id})">删除</button>
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
        showToast('已切换播放源', 'success');
        loadSources();
    } catch (err) {
        showToast('切换失败', 'error');
    }
}

async function deleteSource(id) {
    if (!confirm('确定删除此播放源？')) return;
    try {
        await fetch(`${API}/api/sources/${id}`, { method: 'DELETE' });
        showToast('已删除', 'success');
        loadSources();
    } catch (err) {
        showToast('删除失败', 'error');
    }
}

async function reloadSource(id) {
    try {
        await fetch(`${API}/api/sources/${id}/reload`, { method: 'POST' });
        showToast('正在重新加载…', 'success');
        setTimeout(() => viewChannels(id), 3000);
    } catch (err) {
        showToast('刷新失败', 'error');
    }
}

async function viewChannels(sourceId) {
    try {
        const res = await fetch(`${API}/api/sources/${sourceId}/channels`);
        const data = await res.json();
        renderChannels(data);
    } catch (err) {
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
        const displayGroup = group || '其他';

        html += `
            <div>
                <div class="group-header" onclick="this.nextElementSibling.style.display = this.nextElementSibling.style.display === 'none' ? 'grid' : 'none'">
                    ${escapeHtml(displayGroup)} (${channels.length})
                </div>
                <div class="group-channels">
                    ${channels.map(ch => `
                        <div class="channel-item">
                            ${ch.logoUrl ? `<img src="${escapeHtml(ch.logoUrl)}" alt="" onerror="this.style.display='none'">` : ''}
                            <span>${escapeHtml(ch.displayName)}</span>
                        </div>
                    `).join('')}
                </div>
            </div>
        `;
    });

    countBadge.textContent = `共 ${totalChannels} 个频道`;
    container.innerHTML = html;
    section.style.display = 'block';
}

async function loadSettings() {
    try {
        const res = await fetch(`${API}/api/settings`);
        const settings = await res.json();
        document.getElementById('epgUrl').value = settings.epgUrl || '';
    } catch (err) {
        console.error('Failed to load settings:', err);
    }
}

async function saveSettings() {
    const epgUrl = document.getElementById('epgUrl').value.trim();
    try {
        await fetch(`${API}/api/settings`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ epgUrl })
        });
        showToast('设置已保存', 'success');
    } catch (err) {
        showToast('保存失败', 'error');
    }
}

async function reloadEpg() {
    try {
        const res = await fetch(`${API}/api/epg/reload`, { method: 'POST' });
        const data = await res.json();
        if (data.error) {
            showToast(data.error, 'error');
        } else {
            showToast('EPG 正在刷新…', 'success');
        }
    } catch (err) {
        showToast('EPG 刷新失败', 'error');
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

// Initialize
loadSources();
loadSettings();
