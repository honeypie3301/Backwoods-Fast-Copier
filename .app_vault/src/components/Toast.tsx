import React from 'react';
import { ToastMessage } from '../types';
import { CheckCircle, Info, AlertTriangle, X } from 'lucide-react';

interface ToastProps {
  toasts: ToastMessage[];
  onDismiss: (id: string) => void;
}

export const Toast: React.FC<ToastProps> = ({ toasts, onDismiss }) => {
  if (toasts.length === 0) return null;

  return (
    <div className="fixed bottom-6 right-6 z-50 flex flex-col gap-2 max-w-md w-full px-4 pointer-events-none">
      {toasts.map((toast) => (
        <div
          key={toast.id}
          className="pointer-events-auto flex items-start gap-3 p-3.5 bg-neutral-950 border border-neutral-800 rounded-xl shadow-2xl animate-slide-up text-xs font-mono"
        >
          {toast.type === 'success' && <CheckCircle className="w-4 h-4 text-emerald-400 shrink-0 mt-0.5" />}
          {toast.type === 'info' && <Info className="w-4 h-4 text-cyan-400 shrink-0 mt-0.5" />}
          {toast.type === 'error' && <AlertTriangle className="w-4 h-4 text-rose-400 shrink-0 mt-0.5" />}

          <div className="flex-1 min-w-0">
            <h4 className="font-bold text-white leading-tight">{toast.title}</h4>
            <p className="text-neutral-400 mt-0.5 leading-normal">{toast.description}</p>
          </div>

          <button
            onClick={() => onDismiss(toast.id)}
            className="text-neutral-500 hover:text-white p-0.5 rounded shrink-0"
          >
            <X className="w-4 h-4" />
          </button>
        </div>
      ))}
    </div>
  );
};
