/**
 * Ollama Image Filter — Frontend JavaScript
 * Handles all AJAX interactions with the Spring Boot backend.
 * Features: progress bar, prompt auto-save/restore.
 */

// ── Utility ────────────────────────────────────────────────────────

function showLoading(text = '处理中...') {
    document.getElementById('loadingText').textContent = text;
    document.getElementById('loadingOverlay').style.display = 'flex';
}

function hideLoading() {
    document.getElementById('loadingOverlay').style.display = 'none';
}

function renderMarkdown(elementId, text) {
    const el = document.getElementById(elementId);
    if (text && typeof marked !== 'undefined') {
        el.innerHTML = marked.parse(text);
    } else {
        el.innerText = text || '';
    }
}

async function apiPost(url, data = {}) {
    const formData = new URLSearchParams();
    for (const [k, v] of Object.entries(data)) {
        formData.append(k, v);
    }
    const resp = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: formData.toString()
    });
    if (!resp.ok) {
        throw new Error(`HTTP ${resp.status}: ${resp.statusText}`);
    }
    return resp.json();
}

async function apiGet(url) {
    const resp = await fetch(url);
    if (!resp.ok) {
        throw new Error(`HTTP ${resp.status}: ${resp.statusText}`);
    }
    return resp.json();
}

// ── Temperature slider sync ────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
    const tempSlider = document.getElementById('temperature');
    const tempValue = document.getElementById('tempValue');
    if (tempSlider && tempValue) {
        tempSlider.addEventListener('input', () => {
            tempValue.textContent = parseFloat(tempSlider.value).toFixed(2);
        });
    }

    // Initialize prompt text from the selected preset (only if no saved value)
    const promptPreset = document.getElementById('promptPreset');
    const promptText = document.getElementById('promptText');
    if (promptPreset && promptText) {
        // If textarea already has a saved value from server, keep it
        if (!promptText.value.trim()) {
            const selected = promptPreset.options[promptPreset.selectedIndex];
            promptText.value = selected.dataset.prompt || '';
        }
    }

    // Initialize batch prompt text
    const batchPreset = document.getElementById('batchPromptPreset');
    const batchPrompt = document.getElementById('batchPrompt');
    if (batchPreset && batchPrompt) {
        if (!batchPrompt.value.trim()) {
            const selected = batchPreset.options[batchPreset.selectedIndex];
            batchPrompt.value = selected.dataset.prompt || '';
        }
    }

    // Dedup toggle: show/hide window size options
    const dedupCheckbox = document.getElementById('dedupEnabled');
    const dedupOptions = document.getElementById('dedupOptions');
    if (dedupCheckbox && dedupOptions) {
        dedupCheckbox.addEventListener('change', () => {
            dedupOptions.style.display = dedupCheckbox.checked ? 'block' : 'none';
        });
    }

    // Auto-save prompts when user types (debounced)
    setupPromptAutoSave();
});

// ── Prompt auto-save (debounced) ────────────────────────────────────

let promptSaveTimer = null;

function setupPromptAutoSave() {
    const singlePrompt = document.getElementById('promptText');
    const batchPrompt = document.getElementById('batchPrompt');

    if (singlePrompt) {
        singlePrompt.addEventListener('input', () => {
            clearTimeout(promptSaveTimer);
            promptSaveTimer = setTimeout(() => savePrompts(), 800);
        });
    }
    if (batchPrompt) {
        batchPrompt.addEventListener('input', () => {
            clearTimeout(promptSaveTimer);
            promptSaveTimer = setTimeout(() => savePrompts(), 800);
        });
    }
}

async function savePrompts() {
    const singlePrompt = document.getElementById('promptText');
    const batchPrompt = document.getElementById('batchPrompt');
    const data = {};
    if (singlePrompt) data.singlePrompt = singlePrompt.value;
    if (batchPrompt) data.batchPrompt = batchPrompt.value;
    try {
        await apiPost('/api/prompt-history', data);
    } catch (e) {
        // Silently ignore — prompt save is best-effort
        console.debug('Prompt auto-save skipped', e);
    }
}

// ── Tab 1: Single Analysis ─────────────────────────────────────────

async function testConnection() {
    try {
        const data = await apiPost('/api/connect-test');
        document.getElementById('connectStatus').innerHTML =
            '<span class="text-success">' + data.status + '</span>';
    } catch (e) {
        document.getElementById('connectStatus').innerHTML =
            '<span class="text-danger">连接失败</span>';
    }
}

async function refreshModels() {
    try {
        const data = await apiPost('/api/refresh-models');
        const select = document.getElementById('modelSelect');
        select.innerHTML = '';
        data.models.forEach(m => {
            const opt = document.createElement('option');
            opt.value = m;
            opt.textContent = m;
            select.appendChild(opt);
        });
        if (data.default) select.value = data.default;

        // Also update batch model select
        const batchSelect = document.getElementById('batchModel');
        if (batchSelect) {
            batchSelect.innerHTML = select.innerHTML;
        }
    } catch (e) {
        console.error('Failed to refresh models', e);
    }
}

async function scanImages() {
    try {
        const data = await apiPost('/api/scan');
        document.getElementById('scanStatus').innerHTML =
            '<span class="text-success">' + data.status + '</span>';
        const select = document.getElementById('fileSelect');
        select.innerHTML = '<option value="">-- 选择图片 --</option>';
        data.files.forEach(f => {
            const opt = document.createElement('option');
            opt.value = f;
            opt.textContent = f;
            select.appendChild(opt);
        });
        if (data.files.length > 0) select.value = data.files[0];
    } catch (e) {
        document.getElementById('scanStatus').innerHTML =
            '<span class="text-danger">扫描失败</span>';
    }
}

async function previewImage() {
    const filename = document.getElementById('fileSelect').value;
    if (!filename) {
        document.getElementById('imagePreview').style.display = 'none';
        document.getElementById('previewPlaceholder').style.display = 'block';
        return;
    }
    try {
        const data = await apiPost('/api/preview', { filename });
        const img = document.getElementById('imagePreview');
        if (data.imageUrl) {
            img.src = data.imageUrl;
            img.style.display = 'block';
            document.getElementById('previewPlaceholder').style.display = 'none';
        }
        renderMarkdown('resultMarkdown', data.info);
    } catch (e) {
        console.error('Preview failed', e);
    }
}

async function analyzeImage() {
    const filename = document.getElementById('fileSelect').value;
    const prompt = document.getElementById('promptText').value;
    const model = document.getElementById('modelSelect').value;
    const temperature = document.getElementById('temperature').value;

    if (!filename) {
        renderMarkdown('resultMarkdown', '❌ 请先选择一张图片');
        return;
    }
    if (!prompt.trim()) {
        renderMarkdown('resultMarkdown', '❌ 请输入提示词');
        return;
    }

    // Save prompt before analysis
    savePrompts();

    showLoading('AI 分析中...');
    try {
        const data = await apiPost('/api/analyze', {
            filename, prompt, model, temperature
        });
        if (data.error) {
            renderMarkdown('resultMarkdown', data.error);
        } else {
            renderMarkdown('resultMarkdown', data.info);
            // Show image
            const img = document.getElementById('imagePreview');
            if (data.imageUrl) {
                img.src = data.imageUrl;
                img.style.display = 'block';
                document.getElementById('previewPlaceholder').style.display = 'none';
            }
        }
    } catch (e) {
        renderMarkdown('resultMarkdown', '❌ 分析请求失败: ' + e.message);
    } finally {
        hideLoading();
    }
}

// ── Prompt Select ──────────────────────────────────────────────────

function onSelectPrompt() {
    const select = document.getElementById('promptPreset');
    const selected = select.options[select.selectedIndex];
    const promptText = document.getElementById('promptText');
    const isCustom = selected.dataset.custom === 'true';

    promptText.value = isCustom ? '' : (selected.dataset.prompt || '');
    // Save the change
    savePrompts();
}

function onBatchSelectPrompt() {
    const select = document.getElementById('batchPromptPreset');
    const selected = select.options[select.selectedIndex];
    const batchPrompt = document.getElementById('batchPrompt');
    const isCustom = selected.dataset.custom === 'true';

    batchPrompt.value = isCustom ? '' : (selected.dataset.prompt || '');
    // Save the change
    savePrompts();
}

// ── Progress Bar Helpers ────────────────────────────────────────────

function resetProgressBar() {
    const bar = document.getElementById('batchProgressBar');
    bar.style.width = '0%';
    bar.textContent = '';
    bar.classList.add('progress-bar-striped', 'progress-bar-animated');
    bar.classList.remove('bg-success', 'bg-danger');
    document.getElementById('batchProgressPercent').textContent = '0%';
    document.getElementById('batchProgressLabel').textContent = '准备中...';
    document.getElementById('batchProgressContainer').style.display = 'block';
}

function setProgressLabel(text) {
    document.getElementById('batchProgressLabel').textContent = text;
}

function updateProgressBar(completed, total) {
    const bar = document.getElementById('batchProgressBar');
    if (total <= 0) {
        // Unknown total — show indeterminate animation
        bar.style.width = '100%';
        bar.textContent = completed + ' / ?';
        document.getElementById('batchProgressPercent').textContent = '...';
    } else {
        const pct = Math.round((completed / total) * 100);
        document.getElementById('batchProgressPercent').textContent = pct + '%';
        bar.style.width = pct + '%';
        bar.setAttribute('aria-valuenow', pct);
        bar.textContent = completed + ' / ' + total;
    }
    setProgressLabel('处理进度');
}

function finishProgressBar(success) {
    const bar = document.getElementById('batchProgressBar');
    bar.classList.remove('progress-bar-animated');
    if (success) {
        bar.classList.add('bg-success');
        setProgressLabel('✅ 完成');
    } else {
        bar.classList.add('bg-danger');
        setProgressLabel('❌ 失败');
    }
}

// ── Tab 2: Batch Processing ────────────────────────────────────────

async function batchProcess() {
    const prompt = document.getElementById('batchPrompt').value;
    const model = document.getElementById('batchModel').value;
    const temperature = document.getElementById('batchTemperature').value;
    const maxImages = document.getElementById('maxImages').value;
    const dedupEnabled = document.getElementById('dedupEnabled').checked;
    const dedupWindowSize = document.getElementById('dedupWindowSize').value || 5;

    if (!prompt.trim()) {
        renderMarkdown('batchSummary', '❌ 请输入提示词');
        return;
    }

    // Save prompt before batch
    savePrompts();

    // Show progress bar immediately — no spinner overlay
    resetProgressBar();
    renderMarkdown('batchSummary', '');
    document.getElementById('batchDownload').innerHTML = '';

    try {
        // Step 1: Start the batch task
        setProgressLabel('正在启动任务...');
        const startData = await apiPost('/api/batch', {
            prompt, model, temperature, maxImages,
            dedupEnabled, dedupWindowSize
        });

        if (startData.error) {
            renderMarkdown('batchSummary', startData.error);
            finishProgressBar(false);
            return;
        }

        const taskId = startData.taskId;
        setProgressLabel(dedupEnabled ? '任务已启动 (去重模式)...' : '任务已启动，等待后台处理...');
        updateProgressBar(0, 1);

        // Step 2: Poll for progress
        await pollBatchStatus(taskId);

    } catch (e) {
        renderMarkdown('batchSummary', '❌ 批处理失败: ' + e.message);
        finishProgressBar(false);
    }
}

async function pollBatchStatus(taskId) {
    const maxPolls = 1800; // 1 hour max (at 2s intervals)
    let pollCount = 0;

    while (pollCount < maxPolls) {
        await new Promise(resolve => setTimeout(resolve, 2000));
        pollCount++;

        try {
            const data = await apiPost('/api/batch/status', { taskId });

            if (data.error) {
                renderMarkdown('batchSummary', data.error);
                finishProgressBar(false);
                return;
            }

            const { status, total, completed, summary, csvUrl } = data;

            if (status === 'RUNNING') {
                updateProgressBar(completed, total);
                renderMarkdown('batchSummary',
                    '⏳ 批量处理中...\n\n已处理: ' + completed + ' / ' + total + ' 张');
            } else if (status === 'DONE') {
                updateProgressBar(completed, total);
                finishProgressBar(true);
                renderMarkdown('batchSummary', summary || '✅ 处理完成');
                const downloadDiv = document.getElementById('batchDownload');
                if (csvUrl) {
                    downloadDiv.innerHTML = '<a href="' + csvUrl +
                        '" class="btn btn-success btn-sm"><i class="bi bi-download"></i> 下载 CSV</a>';
                } else {
                    downloadDiv.innerHTML = '';
                }
                return;
            } else if (status === 'FAILED') {
                finishProgressBar(false);
                renderMarkdown('batchSummary', summary || '❌ 批处理失败');
                return;
            }
        } catch (e) {
            console.error('Poll failed', e);
            // Continue polling on transient errors
        }
    }

    renderMarkdown('batchSummary', '⚠️ 批处理超时，请检查后台日志');
    finishProgressBar(false);
}

// ── Tab 3: Results Browser ─────────────────────────────────────────

async function refreshCategories() {
    try {
        const data = await apiPost('/api/categories');
        const select = document.getElementById('categoryFilter');
        select.innerHTML = '';
        data.categories.forEach(c => {
            const opt = document.createElement('option');
            opt.value = c;
            opt.textContent = c;
            select.appendChild(opt);
        });
    } catch (e) {
        console.error('Failed to refresh categories', e);
    }
}

async function filterResults() {
    const category = document.getElementById('categoryFilter').value;
    try {
        const data = await apiPost('/api/filter', { category });
        renderMarkdown('filterStats', data.stats);

        const tbody = document.getElementById('resultsTableBody');
        tbody.innerHTML = '';
        if (!data.table || data.table.length === 0) {
            tbody.innerHTML = '<tr><td colspan="4" class="text-center text-muted py-4">' +
                '<i class="bi bi-inbox"></i> 暂无匹配结果</td></tr>';
        } else {
            data.table.forEach(row => {
                const tr = document.createElement('tr');
                row.forEach(cell => {
                    const td = document.createElement('td');
                    td.textContent = cell;
                    tr.appendChild(td);
                });
                tbody.appendChild(tr);
            });
        }
    } catch (e) {
        console.error('Filter failed', e);
    }
}

async function exportResults() {
    const category = document.getElementById('categoryFilter').value;
    try {
        const data = await apiPost('/api/export', { category });
        const statusEl = document.getElementById('exportStatus');
        statusEl.innerHTML = data.status;
        if (data.csvUrl) {
            statusEl.innerHTML += ' <a href="' + data.csvUrl +
                '" class="btn btn-sm btn-success">下载</a>';
        }
    } catch (e) {
        document.getElementById('exportStatus').innerHTML =
            '<span class="text-danger">导出失败</span>';
    }
}

async function clearResults() {
    if (!confirm('确定要清除所有分析结果吗？此操作不可恢复。')) return;
    try {
        const data = await apiPost('/api/clear');
        document.getElementById('filterStats').innerHTML = data.status;
        document.getElementById('resultsTableBody').innerHTML =
            '<tr><td colspan="4" class="text-center text-muted py-4">' +
            '<i class="bi bi-inbox"></i> 暂无结果</td></tr>';
    } catch (e) {
        console.error('Clear failed', e);
    }
}

// ── Settings Tab ───────────────────────────────────────────────────

async function loadSettings() {
    try {
        const data = await apiGet('/api/settings');
        document.getElementById('settingsImageDir').value = data.imageDir || '';
        document.getElementById('settingsResultsDir').value = data.resultsDir || '';
        document.getElementById('settingsDefaultModel').value = data.defaultModel || '';
        document.getElementById('settingsDefaultTemperature').value = data.defaultTemperature || 0.1;

        // Update info table
        document.getElementById('settingsInfoImageDir').textContent = data.imageDir || '-';
        document.getElementById('settingsInfoResultsDir').textContent = data.resultsDir || '-';
        document.getElementById('settingsInfoImageCount').textContent = data.imageCount || 0;

        // Also refresh result count
        try {
            const statsData = await apiPost('/api/categories');
            // count from stats if available
        } catch (e) { /* ignore */ }
    } catch (e) {
        console.error('Failed to load settings', e);
    }
}

async function saveSettings() {
    const imageDir = document.getElementById('settingsImageDir').value.trim();
    const resultsDir = document.getElementById('settingsResultsDir').value.trim();
    const defaultModel = document.getElementById('settingsDefaultModel').value.trim();
    const defaultTemperature = document.getElementById('settingsDefaultTemperature').value;

    const data = {};
    if (imageDir) data.imageDir = imageDir;
    if (resultsDir) data.resultsDir = resultsDir;
    if (defaultModel) data.defaultModel = defaultModel;
    if (defaultTemperature !== undefined) data.defaultTemperature = defaultTemperature;

    const statusEl = document.getElementById('settingsStatus');
    try {
        const result = await apiPost('/api/settings', data);
        if (result.status === 'ok') {
            statusEl.innerHTML = '<span class="text-success">' + result.message + '</span>';
            // Update info table
            document.getElementById('settingsInfoImageDir').textContent = result.imageDir || imageDir;
            document.getElementById('settingsInfoResultsDir').textContent = result.resultsDir || resultsDir;
            document.getElementById('settingsInfoImageCount').textContent = result.imageCount || 0;
            // Re-scan images in the main tab
            if (result.imageDir) {
                scanImages();
            }
        } else {
            statusEl.innerHTML = '<span class="text-danger">' + (result.message || '保存失败') + '</span>';
        }
    } catch (e) {
        statusEl.innerHTML = '<span class="text-danger">保存失败: ' + e.message + '</span>';
    }
}
