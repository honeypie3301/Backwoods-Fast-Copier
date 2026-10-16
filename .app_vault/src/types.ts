export interface FileItem {
  name: string;
  size: number;
  sizeFormatted: string;
  updatedAt: string;
  version: string;
  versionSource: 'filename' | 'content' | 'metadata' | 'default';
  extension: string;
  isText: boolean;
  isEdited?: boolean;
}

export interface FileContentMap {
  [filename: string]: {
    content: string;
    size: number;
    version: string;
  };
}

export type ShortenPreset = 'compact' | 'medium' | 'generous' | 'full';

export interface ToastMessage {
  id: string;
  title: string;
  description: string;
  type: 'success' | 'info' | 'error';
}
