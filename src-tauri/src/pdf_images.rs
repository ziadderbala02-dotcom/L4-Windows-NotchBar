//! Several images → one PDF (one page each), written directly: no browser, no Office.
//! Each image is re-encoded as JPEG and placed centred on an A4 page (portrait or
//! landscape, whichever fits it better).

use std::io::Write;
use std::path::{Path, PathBuf};

use image::codecs::jpeg::JpegEncoder;
use image::{DynamicImage, GenericImageView, RgbImage};

const A4: (f64, f64) = (595.28, 841.89);
const MARGIN: f64 = 24.0;

struct Page {
    jpeg: Vec<u8>,
    px: (u32, u32),
}

fn flatten(img: &DynamicImage) -> RgbImage {
    let rgba = img.to_rgba8();
    let (w, h) = rgba.dimensions();
    let mut out = RgbImage::new(w, h);
    for (x, y, p) in rgba.enumerate_pixels() {
        let a = p[3] as u32;
        let mix = |c: u8| ((c as u32 * a + 255 * (255 - a)) / 255) as u8;
        out.put_pixel(x, y, image::Rgb([mix(p[0]), mix(p[1]), mix(p[2])]));
    }
    out
}

fn load(path: &Path) -> Result<Page, String> {
    let img = image::open(path).map_err(|_| format!("Bild konnte nicht gelesen werden: {}", path.file_name().map(|n| n.to_string_lossy()).unwrap_or_default()))?;
    // Very large photos are scaled to 3000 px on the long side (sharp in print, smaller file).
    let img = if img.width().max(img.height()) > 3000 { img.resize(3000, 3000, image::imageops::FilterType::Lanczos3) } else { img };
    let px = img.dimensions();
    let mut jpeg = Vec::new();
    DynamicImage::ImageRgb8(flatten(&img))
        .write_with_encoder(JpegEncoder::new_with_quality(&mut jpeg, 90))
        .map_err(|e| e.to_string())?;
    Ok(Page { jpeg, px })
}

/// Build the PDF bytes for the given pages.
fn build(pages: &[Page]) -> Vec<u8> {
    let mut out: Vec<u8> = b"%PDF-1.4\n%\xE2\xE3\xCF\xD3\n".to_vec();
    let mut offsets: Vec<usize> = vec![];
    let n = pages.len();
    // Objects: 1 catalog, 2 pages, then per page: page, content, image.
    let page_id = |i: usize| 3 + i * 3;
    let obj = |out: &mut Vec<u8>, offsets: &mut Vec<usize>, body: &[u8]| {
        offsets.push(out.len());
        let id = offsets.len();
        let _ = write!(out, "{id} 0 obj\n");
        out.extend_from_slice(body);
        out.extend_from_slice(b"\nendobj\n");
    };
    obj(&mut out, &mut offsets, b"<< /Type /Catalog /Pages 2 0 R >>");
    let kids: Vec<String> = (0..n).map(|i| format!("{} 0 R", page_id(i))).collect();
    obj(&mut out, &mut offsets, format!("<< /Type /Pages /Kids [{}] /Count {n} >>", kids.join(" ")).as_bytes());
    for (i, p) in pages.iter().enumerate() {
        let (iw, ih) = (p.px.0 as f64, p.px.1 as f64);
        let (pw, ph) = if iw > ih { (A4.1, A4.0) } else { A4 };
        let scale = ((pw - 2.0 * MARGIN) / iw).min((ph - 2.0 * MARGIN) / ih);
        let (w, h) = (iw * scale, ih * scale);
        let (x, y) = ((pw - w) / 2.0, (ph - h) / 2.0);
        let content = format!("q {w:.2} 0 0 {h:.2} {x:.2} {y:.2} cm /Im0 Do Q");
        let id = page_id(i);
        obj(
            &mut out,
            &mut offsets,
            format!("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {pw:.2} {ph:.2}] /Resources << /XObject << /Im0 {} 0 R >> >> /Contents {} 0 R >>", id + 2, id + 1).as_bytes(),
        );
        obj(&mut out, &mut offsets, format!("<< /Length {} >>\nstream\n{content}\nendstream", content.len()).as_bytes());
        let mut img = format!("<< /Type /XObject /Subtype /Image /Width {} /Height {} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length {} >>\nstream\n", p.px.0, p.px.1, p.jpeg.len()).into_bytes();
        img.extend_from_slice(&p.jpeg);
        img.extend_from_slice(b"\nendstream");
        obj(&mut out, &mut offsets, &img);
    }
    let xref = out.len();
    let _ = write!(out, "xref\n0 {}\n0000000000 65535 f \n", offsets.len() + 1);
    for o in &offsets {
        let _ = write!(out, "{o:010} 00000 n \n");
    }
    let _ = write!(out, "trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n", offsets.len() + 1);
    out
}

fn free_path(dir: &Path, stem: &str) -> PathBuf {
    let mut p = dir.join(format!("{stem}.pdf"));
    let mut n = 1;
    while p.exists() {
        p = dir.join(format!("{stem} ({n}).pdf"));
        n += 1;
    }
    p
}

/// All images, in the given order, into one PDF next to the first one.
#[tauri::command]
pub async fn images_to_pdf(paths: Vec<String>) -> Result<String, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let first = Path::new(paths.first().ok_or("Keine Bilder")?);
        let pages = paths.iter().map(|p| load(Path::new(p))).collect::<Result<Vec<_>, _>>()?;
        let stem = first.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_else(|| "Bilder".into());
        let stem = if paths.len() > 1 { format!("{stem} (+{})", paths.len() - 1) } else { stem };
        let out = free_path(first.parent().unwrap_or(Path::new(".")), &stem);
        std::fs::write(&out, build(&pages)).map_err(|e| format!("Kann nicht speichern: {e}"))?;
        Ok(out.to_string_lossy().into_owned())
    })
    .await
    .map_err(|e| e.to_string())?
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn two_pages() {
        let mk = |w, h| {
            let mut jpeg = Vec::new();
            DynamicImage::ImageRgb8(RgbImage::from_pixel(w, h, image::Rgb([200, 30, 30]))).write_with_encoder(JpegEncoder::new_with_quality(&mut jpeg, 80)).unwrap();
            Page { jpeg, px: (w, h) }
        };
        let pdf = build(&[mk(400, 300), mk(300, 600)]);
        let s = String::from_utf8_lossy(&pdf);
        assert!(s.starts_with("%PDF-1.4"));
        assert!(s.contains("/Count 2"));
        assert!(s.trim_end().ends_with("%%EOF"));
        std::fs::write(std::env::temp_dir().join("notch-test.pdf"), &pdf).unwrap();
    }
}
