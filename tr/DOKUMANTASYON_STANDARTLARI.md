# Turkce Dokumantasyon Standartlari

Bu dokuman, `tr/` altindaki Turkce dokumantasyonun tek tip ve bakimi kolay olmasi icin kullanilir.

## Temel Ilkeler

- Basliklar kisa, net ve gorev odakli olmali.
- Teknik terimler tutarli kullanilmali.
- Kod bloklari, API yol isimleri ve dosya adlari kaynak dokumanla uyumlu kalmali.
- Serbest ceviri yerine teknik anlami koruyan ifade tercih edilmeli.

## Onerilen Bolum Sirasi

Uygun olan dokumanlarda asagidaki sira kullanilir:

1. Amac
2. Kapsam veya Guncel Durum
3. Kullanim / Isletim Akisi
4. Yorumlama veya Iyi Pratikler
5. Riskler / Sinirlar
6. Referanslar

## Baslik Formati

- `#` ana baslik
- `##` ana bolum
- `###` alt bolum

Gereksiz derin baslik yapisindan kacinin.

## Dil Kurali

- Kisa ve dogrudan anlatim kullan.
- Teknik olarak yerlesik terimleri zorla Turkcelestirme.
- Ilk geciste gerekirse parantezli aciklama ekle.

## Kod ve Komutlar

- Kod bloklari cevrilmez.
- Komutlar aynen korunur.
- Aciklama satirlari Turkce olur.

## Raporlar

Saglik, benchmark ve durum raporlarinda mumkun oldugunda su yapi kullanilir:

- Kisa ozet
- Tablo
- Yorum
- Risk veya kabul notu

## Guncelleme Kurali

- Yeni kalici Markdown dosyalari icin `tr/` altinda Turkce karsilik eklenir.
- Var olan kaynak dokuman guncellenirse Turkce karsiligi da guncellenir.
- Otomatik benchmark ozetleri mumkun oldugunda Turkce de uretilir.
