import React, { useState, useRef } from 'react';
import { X, Upload } from 'lucide-react';

interface UploadModalProps {
  isOpen: boolean;
  onClose: () => void;
  onUploadSuccess: (filename: string) => void;
}

export const UploadModal: React.FC<UploadModalProps> = ({ isOpen, onClose, onUploadSuccess }) => {
  const [isDragging, setIsDragging] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  if (!isOpen) return null;

  const handleFiles = async (files: FileList | null) => {
    if (!files || files.length === 0) return;
    setUploading(true);
    setError(null);

    try {
      for (let i = 0; i < files.length; i++) {
        const file = files[i];
        const text = await file.text();

        const res = await fetch('/api/files', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            name: file.name,
            content: text
          })
        });

        const data = await res.json();
        if (data.success) {
          onUploadSuccess(file.name);
        } else {
          setError(data.error || 'Failed to upload file');
        }
      }
      onClose();
    } catch (err: any) {
      setError(err.message || 'Error processing files');
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/85 backdrop-blur-sm">
      <div className="bg-[#050806] border border-[#16271a] rounded-xl w-full max-w-lg p-6 shadow-2xl space-y-5 font-mono">
        
        <div className="flex items-center justify-between border-b border-[#111e14] pb-4">
          <div className="flex items-center gap-2">
            <Upload className="w-5 h-5 text-emerald-400" />
            <h3 className="text-sm font-bold text-[#d0e0d3]">Upload Files to uploaded_files/</h3>
          </div>
          <button onClick={onClose} className="p-1 text-[#6b8271] hover:text-white rounded">
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Drop Zone */}
        <div
          onDragOver={(e) => {
            e.preventDefault();
            setIsDragging(true);
          }}
          onDragLeave={() => setIsDragging(false)}
          onDrop={(e) => {
            e.preventDefault();
            setIsDragging(false);
            handleFiles(e.dataTransfer.files);
          }}
          onClick={() => fileInputRef.current?.click()}
          className={`border-2 border-dashed rounded-lg p-8 flex flex-col items-center justify-center text-center cursor-pointer transition-all ${
            isDragging
              ? 'border-emerald-400 bg-[#091a0e]'
              : 'border-[#182e1e] bg-[#030604] hover:border-emerald-700/80 hover:bg-[#070e08]'
          }`}
        >
          <input
            type="file"
            ref={fileInputRef}
            multiple
            onChange={(e) => handleFiles(e.target.files)}
            className="hidden"
          />

          <Upload className={`w-8 h-8 mb-3 ${isDragging ? 'text-emerald-400' : 'text-[#485d4f]'}`} />
          
          <p className="text-xs font-semibold text-[#c2d4c5]">
            Click to select or drag & drop files here
          </p>
          <p className="text-[11px] text-[#526658] mt-1">
            Java, JSON, MCFunction, YAML, TOML, Markdown, TXT, etc.
          </p>
        </div>

        {uploading && (
          <div className="flex items-center gap-2 text-xs text-emerald-400 justify-center">
            <span className="w-4 h-4 border-2 border-emerald-400 border-t-transparent rounded-full animate-spin" />
            <span>Uploading files to /uploaded_files directory...</span>
          </div>
        )}

        {error && (
          <div className="p-3 bg-rose-950/60 border border-rose-900 text-rose-300 text-xs rounded">
            {error}
          </div>
        )}

      </div>
    </div>
  );
};
