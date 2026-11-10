// LunexSU module WebUI downloads (adapted from upstream KernelSU's download.js).
// Only blob:, data: and same-origin local files are saved, and only into the public
// Downloads folder through the `ksu_download` bridge. Other links keep the normal
// WebUI navigation policy: nothing here can open an external page.
(function() {
    if (window.__lunexsuDownloadInstalled || typeof ksu_download === 'undefined') return;
    window.__lunexsuDownloadInstalled = true;

    const CHUNK_SIZE = 512 * 1024;
    const LOCAL_ORIGIN = 'https://mui.kernelsu.org';

    // The CSP forbids fetch() of blob:/data:, so remember Blobs as their URLs are created.
    const blobMap = new Map();
    const originalCreateObjectURL = URL.createObjectURL;
    URL.createObjectURL = function(obj) {
        const url = originalCreateObjectURL.apply(this, arguments);
        if (obj instanceof Blob) blobMap.set(url, obj);
        return url;
    };
    const originalRevokeObjectURL = URL.revokeObjectURL;
    URL.revokeObjectURL = function(url) {
        // Pages commonly revoke right after click(); keep the Blob long enough to read it.
        setTimeout(() => blobMap.delete(url), 60000);
        return originalRevokeObjectURL.apply(this, arguments);
    };

    const dataUrlToBlob = (href) => {
        const comma = href.indexOf(',');
        if (comma < 0) throw new Error('Malformed data URL');
        const meta = href.substring(5, comma);
        const body = href.substring(comma + 1);
        const isBase64 = /;base64$/i.test(meta);
        const mimeType = meta.replace(/;base64$/i, '').split(';')[0] || 'application/octet-stream';
        let bytes;
        if (isBase64) {
            const binary = atob(body);
            bytes = new Uint8Array(binary.length);
            for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
        } else {
            bytes = new TextEncoder().encode(decodeURIComponent(body));
        }
        return new Blob([bytes], { type: mimeType });
    };

    const readChunk = (blob) => new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result).split(',')[1] || '');
        reader.onerror = () => reject(reader.error || new Error('Failed to read chunk'));
        reader.readAsDataURL(blob);
    });

    const resolveBlob = async (url) => {
        if (url.protocol === 'blob:') {
            const blob = blobMap.get(url.href);
            if (!blob) throw new Error('Unknown or expired blob URL');
            return blob;
        }
        if (url.protocol === 'data:') return dataUrlToBlob(url.href);
        if (url.origin === LOCAL_ORIGIN) return await (await fetch(url.href)).blob();
        return null;
    };

    const save = async (blob, fileName) => {
        const id = ksu_download.begin(fileName, blob.type || '');
        if (!id) throw new Error('Download rejected');
        try {
            for (let offset = 0; offset < blob.size; offset += CHUNK_SIZE) {
                const chunk = await readChunk(blob.slice(offset, Math.min(offset + CHUNK_SIZE, blob.size)));
                if (!ksu_download.write(id, chunk)) throw new Error('Failed to write chunk');
            }
            if (!ksu_download.finish(id)) throw new Error('Failed to save download');
        } catch (error) {
            ksu_download.cancel(id);
            throw error;
        }
    };

    const savable = (anchor) => {
        if (!anchor || !anchor.hasAttribute('download') || !anchor.href) return null;
        const url = new URL(anchor.href, location.href);
        if (url.protocol === 'blob:' || url.protocol === 'data:' || url.origin === LOCAL_ORIGIN) return url;
        return null;
    };

    const handle = (anchor, url) => {
        const name = anchor.getAttribute('download') ||
            decodeURIComponent(url.pathname.split('/').pop() || '') || 'download.bin';
        resolveBlob(url)
            .then((blob) => blob && save(blob, name))
            .catch((error) => console.error('LunexSU WebUI download failed', error));
    };

    document.addEventListener('click', (event) => {
        const anchor = event.target instanceof Element ? event.target.closest('a[download]') : null;
        const url = savable(anchor);
        if (!url) return;
        event.preventDefault();
        handle(anchor, url);
    }, true);

    // Programmatic downloads: a detached <a download> followed by click().
    const originalClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function() {
        const url = savable(this);
        if (url && !this.isConnected) {
            handle(this, url);
            return;
        }
        return originalClick.apply(this, arguments);
    };
})();
