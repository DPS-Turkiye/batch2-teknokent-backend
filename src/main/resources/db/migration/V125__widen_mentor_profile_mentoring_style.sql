-- "Nasıl çalışıyorum" is a paragraph, one line per point. The request allowed 200 characters (the column 255),
-- which refused real profiles; both now take 2000. Widening a VARCHAR keeps every stored value as it is.
ALTER TABLE mentor_profile ALTER COLUMN mentoring_style TYPE VARCHAR(2000);
