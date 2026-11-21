-- The original file's own size (image at its stored DPI or 300, PDF first page), as measured by the desktop.
-- Null for jobs from older desktops or unreadable files.
ALTER TABLE print_job ADD COLUMN file_width_mm DOUBLE PRECISION;
ALTER TABLE print_job ADD COLUMN file_height_mm DOUBLE PRECISION;
ALTER TABLE print_job ADD COLUMN file_size_source VARCHAR(80);
