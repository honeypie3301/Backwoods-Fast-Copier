import React, { useState } from 'react';
import { X, Plus, Check } from 'lucide-react';

interface NewFileModalProps {
  isOpen: boolean;
  onClose: () => void;
  onCreateFile: (name: string, content: string) => Promise<boolean>;
}

export const NewFileModal: React.FC<NewFileModalProps> = ({ isOpen, onClose, onCreateFile }) => {
  const [fileName, setFileName] = useState('');
  const [content, setContent] = useState('');
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!fileName.trim()) {
      setError('File name is required');
      return;
    }

    setCreating(true);
    setError(null);

    const success = await onCreateFile(fileName.trim(), content);
    setCreating(false);

    if (success) {
      setFileName('');
      setContent('');
      onClose();
    } else {
      setError('Failed to create file');
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/85 backdrop-blur-sm">
      <div className="bg-[#050806] border border-[#16271a] rounded-xl w-full max-w-xl p-6 shadow-2xl space-y-5 font-mono">
        
        <div className="flex items-center justify-between border-b border-[#111e14] pb-4">
          <div className="flex items-center gap-2">
            <Plus className="w-5 h-5 text-emerald-400" />
            <h3 className="text-sm font-bold text-[#d0e0d3]">Create New File in uploaded_files/</h3>
          </div>
          <button onClick={onClose} className="p-1 text-[#6b8271] hover:text-white rounded">
            <X className="w-5 h-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4 font-mono text-xs">
          
          <div>
            <label className="block text-[#829988] font-semibold mb-1">
              File Name (e.g., custom_recipe_1.21.1_config.json)
            </label>
            <input
              type="text"
              value={fileName}
              onChange={(e) => setFileName(e.target.value)}
              placeholder="e.g. BackwoodsMod_v26.1.2_Procedure.java"
              className="w-full px-3 py-2 bg-[#030604] border border-[#16271a] rounded text-[#d0e0d3] placeholder-[#485d4f] focus:outline-none focus:border-emerald-600"
            />
          </div>

          <div>
            <label className="block text-[#829988] font-semibold mb-1">
              File Content
            </label>
            <textarea
              rows={8}
              value={content}
              onChange={(e) => setContent(e.target.value)}
              placeholder="// Paste or type code / JSON / config content here..."
              className="w-full px-3 py-2 bg-[#030604] border border-[#16271a] rounded text-[#c2d4c5] placeholder-[#485d4f] focus:outline-none focus:border-emerald-600 font-mono resize-none leading-relaxed"
            />
          </div>

          {error && (
            <div className="p-2.5 bg-rose-950/60 border border-rose-900 text-rose-300 rounded">
              {error}
            </div>
          )}

          <div className="flex justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-1.5 text-[#718776] hover:text-white bg-[#070c09] border border-[#142217] rounded"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={creating}
              className="flex items-center gap-2 px-5 py-1.5 font-bold text-black bg-emerald-500 hover:bg-emerald-400 rounded transition-all"
            >
              {creating ? (
                <span>Saving...</span>
              ) : (
                <>
                  <Check className="w-4 h-4 stroke-[2.5]" />
                  <span>Create File</span>
                </>
              )}
            </button>
          </div>

        </form>

      </div>
    </div>
  );
};
