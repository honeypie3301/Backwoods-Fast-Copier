/**
 * ============================================================================
 * STRICT RULE / CONSTANT REMINDER:
 * Always fully import so the updated frame logic is always there.
 * ============================================================================
 */

import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import JSZip from 'jszip';
import { FileItem, ShortenPreset, ToastMessage } from './types';
import { Header } from './components/Header';
import { SidebarControls } from './components/SidebarControls';
import { SidebarFileList } from './components/SidebarFileList';
import { RightPanelViewer } from './components/RightPanelViewer';
import { Toast } from './components/Toast';
import { SHORTEN_PRESETS } from './utils/shortenFileName';
import { detectEntityForFile, getEntityLabel, KNOWN_ENTITIES } from './utils/entityDetection';
import { copyToClipboard } from './utils/copyToClipboard';

export default function App() {
  const [files, setFiles] = useState<FileItem[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [isRefreshing, setIsRefreshing] = useState<boolean>(false);
  const [selectedFileName, setSelectedFileName] = useState<string | null>(null);

  // Filters & Controls
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [selectedVersion, setSelectedVersion] = useState<string | null>(null);
  const [selectedEntity, setSelectedEntity] = useState<string | null>(null);
  const [activePreset, setActivePreset] = useState<ShortenPreset>('medium');
  const [maxLength, setMaxLength] = useState<number>(SHORTEN_PRESETS.medium);
  const [showEditedOnly, setShowEditedOnly] = useState<boolean>(false);

  // Tracking state
  const [readFiles, setReadFiles] = useState<Set<string>>(() => {
    try {
      const stored = localStorage.getItem('backwoods_read_files');
      return stored ? new Set(JSON.parse(stored)) : new Set();
    } catch {
      return new Set();
    }
  });
  const [selectedFileNames, setSelectedFileNames] = useState<Set<string>>(new Set());
  const [editedFileNames, setEditedFileNames] = useState<Set<string>>(new Set());
  const [copiedSignatures, setCopiedSignatures] = useState<Record<string, string>>(() => {
    try {
      const stored = localStorage.getItem('backwoods_copied_signatures');
      return stored ? JSON.parse(stored) : {};
    } catch {
      return {};
    }
  });
  const [contentCache, setContentCache] = useState<Record<string, string>>({});
  const [isZipping, setIsZipping] = useState<boolean>(false);
  const fileSignaturesRef = useRef<Record<string, string>>({});

  // Notifications
  const [toasts, setToasts] = useState<ToastMessage[]>([]);
  const [liveSyncStatus, setLiveSyncStatus] = useState<'connected' | 'connecting' | 'disconnected'>('connecting');

  const addToast = useCallback((title: string, description: string, type: 'success' | 'info' | 'error' = 'info') => {
    const id = Date.now().toString() + Math.random().toString(36).substring(2, 6);
    setToasts((prev) => [...prev, { id, title, description, type }]);
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id));
    }, 4000);
  }, []);

  const dismissToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  // Fetch file list
  const fetchFiles = useCallback(async (isManualRefresh = false, isSilent = false) => {
    if (!isSilent) {
      if (isManualRefresh) setIsRefreshing(true);
      else setLoading(true);
    }

    try {
      const res = await fetch('/api/files', { cache: 'no-store' });
      const data = await res.json();
      if (data.success && Array.isArray(data.files)) {
        const incomingFiles: FileItem[] = data.files;
        const prevSigs = fileSignaturesRef.current;
        const hasPrevSigs = Object.keys(prevSigs).length > 0;
        const changedNames: string[] = [];
        const nextSigs: Record<string, string> = {};

        for (const f of incomingFiles) {
          const sig = `${f.updatedAt}:${f.size}`;
          nextSigs[f.name] = sig;
          if (hasPrevSigs && prevSigs[f.name] !== undefined && prevSigs[f.name] !== sig) {
            changedNames.push(f.name);
          }
        }
        fileSignaturesRef.current = nextSigs;

        if (changedNames.length > 0 || isManualRefresh) {
          setContentCache((prev) => {
            if (isManualRefresh && changedNames.length === 0) {
              return {};
            }
            const next = { ...prev };
            for (const name of changedNames) {
              delete next[name];
            }
            return next;
          });
        }

        if (changedNames.length > 0) {
          setEditedFileNames((prev) => {
            const next = new Set(prev);
            changedNames.forEach((n) => next.add(n));
            return next;
          });
          setReadFiles((prev) => {
            const next = new Set(prev);
            changedNames.forEach((n) => next.delete(n));
            try {
              localStorage.setItem('backwoods_read_files', JSON.stringify(Array.from(next)));
            } catch {
              // ignore
            }
            return next;
          });
        }

        setFiles((current) => {
          if (
            current.length === incomingFiles.length &&
            changedNames.length === 0 &&
            current.every((f, i) => f.name === incomingFiles[i].name && f.updatedAt === incomingFiles[i].updatedAt)
          ) {
            return current;
          }
          return incomingFiles;
        });
        setSelectedFileName((prevSelected) => {
          if (prevSelected && incomingFiles.some((f) => f.name === prevSelected)) {
            return prevSelected;
          }
          return null;
        });
      } else if (!isSilent) {
        addToast('Failed to load files', data.error || 'Server returned an error', 'error');
      }
    } catch (err: any) {
      if (!isSilent) {
        addToast('Connection Error', err.message || 'Could not connect to /api/files', 'error');
      }
    } finally {
      if (!isSilent) {
        setLoading(false);
        setIsRefreshing(false);
      }
    }
  }, [addToast]);

  // Initial load, window focus sync, and SSE real-time stream
  useEffect(() => {
    fetchFiles(false, false);
    const handleFocus = () => fetchFiles(false, true);
    window.addEventListener('focus', handleFocus);

    let es: EventSource | null = null;
    let reconnectTimer: NodeJS.Timeout | null = null;

    const connectSSE = () => {
      setLiveSyncStatus('connecting');
      try {
        es = new EventSource('/api/files/stream');

        es.onopen = () => {
          setLiveSyncStatus('connected');
        };

        es.onmessage = (event) => {
          try {
            const data = JSON.parse(event.data);
            if (data.type === 'files_changed') {
              // Silent immediate refresh on disk change
              fetchFiles(false, true);
            }
          } catch {
            // keepalive or non-json message
          }
        };

        es.onerror = () => {
          setLiveSyncStatus('disconnected');
          if (es) {
            es.close();
            es = null;
          }
          if (!reconnectTimer) {
            reconnectTimer = setTimeout(() => {
              reconnectTimer = null;
              connectSSE();
            }, 3000);
          }
        };
      } catch {
        setLiveSyncStatus('disconnected');
      }
    };

    connectSSE();

    return () => {
      window.removeEventListener('focus', handleFocus);
      if (es) es.close();
      if (reconnectTimer) clearTimeout(reconnectTimer);
    };
  }, [fetchFiles]);

  // Sync readFiles to localStorage
  const markAsRead = useCallback((filename: string) => {
    setReadFiles((prev) => {
      if (prev.has(filename)) return prev;
      const next = new Set(prev);
      next.add(filename);
      try {
        localStorage.setItem('backwoods_read_files', JSON.stringify(Array.from(next)));
      } catch {
        // ignore
      }
      return next;
    });
  }, []);

  const handleMarkAllRead = useCallback(() => {
    const allNames = new Set(files.map((f) => f.name));
    setReadFiles(allNames);
    setEditedFileNames(new Set());
    setCopiedSignatures((prev) => {
      const next = { ...prev };
      files.forEach((f) => {
        next[f.name] = `${f.updatedAt}:${f.size}`;
      });
      Object.entries(fileSignaturesRef.current).forEach(([name, sig]) => {
        next[name] = sig;
      });
      try {
        localStorage.setItem('backwoods_copied_signatures', JSON.stringify(next));
      } catch {
        // ignore
      }
      return next;
    });
    try {
      localStorage.setItem('backwoods_read_files', JSON.stringify(Array.from(allNames)));
    } catch {
      // ignore
    }
    addToast('Marked All as Read', 'All files and updated indicators have been cleared.', 'info');
  }, [files, addToast]);

  // Available versions derived dynamically
  const availableVersions = useMemo(() => {
    const verSet = new Set<string>();
    files.forEach((f) => {
      if (f.version) verSet.add(f.version);
    });
    return Array.from(verSet).sort();
  }, [files]);

  // Available entities with counts
  const availableEntities = useMemo(() => {
    const counts: Record<string, number> = {};
    files.forEach((f) => {
      const entId = detectEntityForFile(f.name);
      counts[entId] = (counts[entId] || 0) + 1;
    });

    return KNOWN_ENTITIES
      .filter((ent) => counts[ent.id] > 0)
      .map((ent) => ({
        id: ent.id,
        label: ent.label,
        count: counts[ent.id] || 0,
      }));
  }, [files]);

  /**
   * ============================================================================
   * STRICT RULE / CONSTANT REMINDER:
   * The updated indicator will ONLY appear if the file is updated, and will ONLY
   * disappear through the "Mark as Read" button or when the file content is copied.
   * Selecting or viewing a file MUST NOT clear the updated indicator.
   * ============================================================================
   */

  // Enriched files with isEdited (strictly preserved until copied or marked as read)
  const enrichedFiles = useMemo(() => {
    return files.map((f) => {
      const sig = `${f.updatedAt}:${f.size}`;
      const isCopiedCurrent = copiedSignatures[f.name] === sig;
      return {
        ...f,
        isEdited: !isCopiedCurrent && Boolean(f.isEdited || editedFileNames.has(f.name)),
      };
    });
  }, [files, editedFileNames, copiedSignatures]);

  // Clears the updated indicator strictly when marked as read or copied
  const clearUpdatedIndicator = useCallback((filename: string) => {
    setEditedFileNames((prev) => {
      if (!prev.has(filename)) return prev;
      const next = new Set(prev);
      next.delete(filename);
      return next;
    });
    setCopiedSignatures((prev) => {
      const target = files.find((f) => f.name === filename);
      const sig = target ? `${target.updatedAt}:${target.size}` : fileSignaturesRef.current[filename];
      if (!sig || prev[filename] === sig) return prev;
      const next = { ...prev, [filename]: sig };
      try {
        localStorage.setItem('backwoods_copied_signatures', JSON.stringify(next));
      } catch {
        // ignore
      }
      return next;
    });
  }, [files]);

  // Dedicated Mark as Read handler conforming to the strict rule
  const handleMarkAsRead = useCallback((filename: string) => {
    markAsRead(filename);
    clearUpdatedIndicator(filename);
    addToast('Marked as Read', `Cleared updated indicator for ${filename}`, 'info');
  }, [markAsRead, clearUpdatedIndicator, addToast]);

  const editedCount = useMemo(() => {
    return enrichedFiles.filter((f) => f.isEdited).length;
  }, [enrichedFiles]);

  useEffect(() => {
    if (showEditedOnly && editedCount === 0) {
      setShowEditedOnly(false);
    }
  }, [showEditedOnly, editedCount]);

  // Filtered files
  const filteredFiles = useMemo(() => {
    return enrichedFiles.filter((file) => {
      if (showEditedOnly && !file.isEdited) {
        return false;
      }

      if (selectedVersion && file.version !== selectedVersion) {
        return false;
      }

      if (selectedEntity) {
        const entId = detectEntityForFile(file.name);
        if (entId !== selectedEntity) return false;
      }

      if (searchQuery.trim()) {
        const q = searchQuery.toLowerCase().trim();
        const matchesName = file.name.toLowerCase().includes(q);
        const matchesVersion = file.version.toLowerCase().includes(q);
        const entityLabel = getEntityLabel(detectEntityForFile(file.name)).toLowerCase();
        const matchesEntity = entityLabel.includes(q);
        if (!matchesName && !matchesVersion && !matchesEntity) {
          return false;
        }
      }

      return true;
    });
  }, [enrichedFiles, showEditedOnly, selectedVersion, selectedEntity, searchQuery]);

  // Statistics
  const totalSizeFormatted = useMemo(() => {
    const totalBytes = files.reduce((acc, f) => acc + f.size, 0);
    if (totalBytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(totalBytes) / Math.log(k));
    return parseFloat((totalBytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }, [files]);

  const unreadCount = useMemo(() => {
    return files.filter((f) => !readFiles.has(f.name)).length;
  }, [files, readFiles]);

  const selectedFile = useMemo(() => {
    if (!selectedFileName) return null;
    return enrichedFiles.find((f) => f.name === selectedFileName) || null;
  }, [enrichedFiles, selectedFileName]);

  // Selection actions for ZIP
  const handleToggleSelectFile = useCallback((filename: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setSelectedFileNames((prev) => {
      const next = new Set(prev);
      if (next.has(filename)) {
        next.delete(filename);
      } else {
        next.add(filename);
      }
      return next;
    });
  }, []);

  const handleSelectAllFiltered = useCallback(() => {
    setSelectedFileNames(new Set(filteredFiles.map((f) => f.name)));
  }, [filteredFiles]);

  const handleDeselectAll = useCallback(() => {
    setSelectedFileNames(new Set());
  }, []);

  // View file in right panel
  const handleSelectFile = useCallback((file: FileItem) => {
    setSelectedFileName(file.name);
    // STRICT RULE REMINDER: Selecting or viewing a file MUST NOT clear the updated indicator.
    // The updated indicator ONLY disappears through the "Mark as Read" button or when copied.
  }, []);

  // Copy file content directly
  const handleCopyContent = useCallback(async (filename: string): Promise<boolean> => {
    try {
      let text = contentCache[filename];
      if (text === undefined) {
        const res = await fetch(`/api/files/${encodeURIComponent(filename)}/content?raw=1`, {
          cache: 'no-store',
          headers: { Accept: 'text/plain' },
        });
        if (!res.ok) {
          addToast('Copy Failed', 'Failed to fetch file content', 'error');
          return false;
        }
        text = await res.text();
        setContentCache((prev) => ({ ...prev, [filename]: text }));
      }

      const ok = await copyToClipboard(text);
      if (ok) {
        markAsRead(filename);
        clearUpdatedIndicator(filename);
        return true;
      }
      addToast('Copy Failed', 'Unable to access clipboard.', 'error');
      return false;
    } catch (err: any) {
      addToast('Copy Failed', err.message || 'Error copying content', 'error');
      return false;
    }
  }, [contentCache, markAsRead, clearUpdatedIndicator, addToast]);

  // Save edited file
  const handleSaveContent = useCallback(async (filename: string, newContent: string): Promise<boolean> => {
    try {
      const res = await fetch('/api/files', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: filename, content: newContent }),
      });
      const data = await res.json();
      if (data.success) {
        setContentCache((prev) => ({ ...prev, [filename]: newContent }));
        setEditedFileNames((prev) => new Set(prev).add(filename));
        setCopiedSignatures((prev) => {
          if (!(filename in prev)) return prev;
          const next = { ...prev };
          delete next[filename];
          try {
            localStorage.setItem('backwoods_copied_signatures', JSON.stringify(next));
          } catch {
            // ignore
          }
          return next;
        });
        markAsRead(filename);
        addToast('File Saved', `${filename} saved successfully.`, 'success');
        fetchFiles(true);
        return true;
      } else {
        addToast('Save Failed', data.error || 'Server error', 'error');
        return false;
      }
    } catch (err: any) {
      addToast('Save Error', err.message, 'error');
      return false;
    }
  }, [fetchFiles, markAsRead, addToast]);

  // Delete file
  const handleDeleteFile = useCallback(async (filename: string) => {
    if (!confirm(`Are you sure you want to delete ${filename}?`)) return;

    try {
      const res = await fetch(`/api/files/${encodeURIComponent(filename)}`, {
        method: 'DELETE',
      });
      const data = await res.json();
      if (data.success) {
        addToast('File Deleted', `${filename} was deleted.`, 'info');
        if (selectedFileName === filename) {
          setSelectedFileName(null);
        }
        fetchFiles(true);
      } else {
        addToast('Delete Failed', data.error || 'Server error', 'error');
      }
    } catch (err: any) {
      addToast('Delete Error', err.message, 'error');
    }
  }, [selectedFileName, fetchFiles, addToast]);

  // Create file
  // Download ZIP
  const handleDownloadZip = useCallback(async () => {
    setIsZipping(true);
    try {
      const zip = new JSZip();
      const filesToZip = selectedFileNames.size > 0
        ? files.filter((f) => selectedFileNames.has(f.name))
        : filteredFiles;

      if (filesToZip.length === 0) {
        addToast('No files to export', 'Select at least one file or adjust filters.', 'error');
        setIsZipping(false);
        return;
      }

      // Prefetch batch contents for uncached files
      const uncached = filesToZip.map((f) => f.name).filter((name) => contentCache[name] === undefined);
      let batchCache = { ...contentCache };

      if (uncached.length > 0) {
        const res = await fetch('/api/files/batch-contents', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ filenames: uncached }),
        });
        const data = await res.json();
        if (data.success && data.contents) {
          const fetchedMap: Record<string, string> = {};
          Object.entries(data.contents).forEach(([k, v]: [string, any]) => {
            fetchedMap[k] = v.content;
          });
          batchCache = { ...batchCache, ...fetchedMap };
          setContentCache((prev) => ({ ...prev, ...fetchedMap }));
        }
      }

      // Add each file to ZIP
      for (const f of filesToZip) {
        let text = batchCache[f.name];
        if (text === undefined) {
          const res = await fetch(`/api/files/${encodeURIComponent(f.name)}/content?raw=1`, {
            cache: 'no-store',
            headers: { Accept: 'text/plain' },
          });
          text = res.ok ? await res.text() : '';
        }
        zip.file(f.name, text);
      }

      const blob = await zip.generateAsync({ type: 'blob' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `backwoods_files_${new Date().toISOString().slice(0, 10)}.zip`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);

      addToast('ZIP Archive Generated', `Successfully exported ${filesToZip.length} files.`, 'success');
    } catch (err: any) {
      addToast('Export Failed', err.message || 'Error generating ZIP', 'error');
    } finally {
      setIsZipping(false);
    }
  }, [files, filteredFiles, selectedFileNames, contentCache, addToast]);

  return (
    <div className="h-screen w-screen flex flex-col bg-[#020403] text-[#d0e0d3] overflow-hidden select-none font-mono">
      {/* Top Header */}
      <Header
        fileCount={files.length}
        totalSizeFormatted={totalSizeFormatted}
        isRefreshing={isRefreshing}
        editedCount={editedCount}
        liveSyncStatus={liveSyncStatus}
        onRefresh={() => fetchFiles(true, false)}
      />

      {/* Main Dual-Panel View */}
      <div className="flex-1 flex overflow-hidden">
        {/* Left Sidebar: Controls & File List */}
        <aside className="w-96 md:w-[460px] lg:w-[520px] shrink-0 border-r border-[#111a13] flex flex-col h-full bg-[#030604] overflow-hidden">
          <SidebarControls
            searchQuery={searchQuery}
            onSearchChange={setSearchQuery}
            availableVersions={availableVersions}
            selectedVersion={selectedVersion}
            onVersionSelect={setSelectedVersion}
            availableEntities={availableEntities}
            selectedEntity={selectedEntity}
            onEntitySelect={setSelectedEntity}
            maxLength={maxLength}
            onMaxLengthChange={setMaxLength}
            activePreset={activePreset}
            onPresetSelect={setActivePreset}
            totalFiltered={filteredFiles.length}
            totalCount={files.length}
            unreadCount={unreadCount}
            editedCount={editedCount}
            showEditedOnly={showEditedOnly}
            onToggleEditedOnly={() => setShowEditedOnly(!showEditedOnly)}
            onMarkAllRead={handleMarkAllRead}
            selectedCount={selectedFileNames.size}
            onSelectAllFiltered={handleSelectAllFiltered}
            onDeselectAll={handleDeselectAll}
            onDownloadZip={handleDownloadZip}
            isZipping={isZipping}
          />

          {/* Scrollable File List */}
          <div className="flex-1 overflow-y-auto">
            {loading ? (
              <div className="h-40 flex items-center justify-center text-xs text-[#4e6153] gap-2">
                <span className="w-3.5 h-3.5 border-2 border-emerald-500 border-t-transparent rounded-full animate-spin" />
                <span>Loading files...</span>
              </div>
            ) : (
              <SidebarFileList
                files={filteredFiles}
                selectedFileName={selectedFileName}
                maxLength={maxLength}
                readFiles={readFiles}
                selectedFileNames={selectedFileNames}
                onToggleSelectFile={handleToggleSelectFile}
                onSelectFile={handleSelectFile}
                onCopyContent={handleCopyContent}
                onMarkAsRead={handleMarkAsRead}
              />
            )}
          </div>
        </aside>

        {/* Right Panel: Content Inspector & Live Editor */}
        <main className="flex-1 h-full overflow-hidden flex flex-col bg-[#030504]">
          <RightPanelViewer
            file={selectedFile}
            cachedContent={selectedFile ? contentCache[selectedFile.name] : undefined}
            onClose={() => setSelectedFileName(null)}
            onSaveContent={handleSaveContent}
            onDeleteFile={handleDeleteFile}
            onCopyContent={handleCopyContent}
            onMarkAsRead={handleMarkAsRead}
          />
        </main>
      </div>

      {/* Toast Notifications */}
      <Toast toasts={toasts} onDismiss={dismissToast} />
    </div>
  );
}
