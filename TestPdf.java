package com.suning.snbc.developer.portal.controller;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.itextpdf.awt.geom.Rectangle;
import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Chunk;
import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Font;
import com.itextpdf.text.Image;
import com.itextpdf.text.List;
import com.itextpdf.text.ListItem;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.log.SysoCounter;
import com.itextpdf.text.pdf.AcroFields;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.pdf.PdfContentByte;
import com.itextpdf.text.pdf.PdfCopy;
import com.itextpdf.text.pdf.PdfImportedPage;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.PdfStamper;
import com.itextpdf.text.pdf.PdfWriter;

public class TestPdf {

	
//	http://www.downza.cn/soft/20562.html
//	https://www.cnblogs.com/wangpeng00700/p/8418594.html
//	https://blog.csdn.net/weixin_36049035/article/details/82909917
	public static String FILE_DIR = "E:\\tmp\\ss\\";
	public static void main(String[] args) throws FileNotFoundException, DocumentException {
//		//Step 1—Create a Document.  
//		Document document = new Document();  
//		//Step 2—Get a PdfWriter instance.  
//		PdfWriter.getInstance(document, new FileOutputStream(FILE_DIR + "2PDF.pdf"));  
//		//Step 3—Open the Document.  
//		document.open();  
//		//Step 4—Add content.  
////		document.add(new Paragraph("Hello World"));  
//		//Step 5—Close the Document.  
//		document.close();
		
		
		Map<String,Object> o = new HashMap<String, Object>();
		Map<String,Object> datemap = new HashMap<String, Object>();
		datemap.put("year", "9999");
		datemap.put("month", "99");
		datemap.put("day", "9");
		datemap.put("taskname", "jzw测试任务名");
		o.put("datemap", datemap);

		Map<String,Object> imgmap = new HashMap<String, Object>();
		imgmap.put("img",FILE_DIR + "java.jpg");
		
		o.put("imgmap", imgmap);
		pdfout(o);
		System.out.println("end");
	}
	
	
	
	// 利用模板生成pdf  
    public static void pdfout(Map<String,Object> o) {
        // 模板路径  
        String templatePath = FILE_DIR + "jzwp.pdf";
        // 生成的新文件路径  
        String newPDFPath = FILE_DIR + "jzwr4444444.pdf";

        PdfReader reader;
        FileOutputStream out;
        ByteArrayOutputStream bos;
        PdfStamper stamper;
        try {
            BaseFont bf = BaseFont.createFont("c://windows//fonts//simsun.ttc,1" , BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            Font FontChinese = new Font(bf, 5, Font.NORMAL);
            out = new FileOutputStream(newPDFPath);// 输出流
            reader = new PdfReader(templatePath);// 读取pdf模板  
            bos = new ByteArrayOutputStream();
            stamper = new PdfStamper(reader, bos);
            AcroFields form = stamper.getAcroFields();
            //文字类的内容处理
            Map<String,String> datemap = (Map<String,String>)o.get("datemap");
            form.addSubstitutionFont(bf);
            for(String key : datemap.keySet()){
                String value = datemap.get(key);
                form.setField(key,value);
            }
            //图片类的内容处理
            Map<String,String> imgmap = (Map<String,String>)o.get("imgmap");
            for(String key : imgmap.keySet()) {
                String value = imgmap.get(key);
                String imgpath = value;
                int pageNo = form.getFieldPositions(key).get(0).page;
                com.itextpdf.text.Rectangle signRect = form.getFieldPositions(key).get(0).position;
                float x = signRect.getLeft();
                float y = signRect.getBottom();
                //根据路径读取图片
                Image image = Image.getInstance(imgpath);
                //获取图片页面
                PdfContentByte under = stamper.getOverContent(pageNo);
                //图片大小自适应
                image.scaleToFit(signRect.getWidth(), signRect.getHeight());
                //添加图片
                image.setAbsolutePosition(x, y);
                under.addImage(image);
            }
            stamper.setFormFlattening(true);// 如果为false，生成的PDF文件可以编辑，如果为true，生成的PDF文件不可以编辑
            stamper.close();
            Document doc = new Document();
            Font font = new Font(bf, 32);
            PdfCopy copy = new PdfCopy(doc, out);
            doc.open();
            for(int i=1;i<5;i++){
            	PdfImportedPage importPage = copy.getImportedPage(new PdfReader(bos.toByteArray()), i);
                copy.addPage(importPage);
            }
            
            doc.close();

        } catch (IOException e) {
            System.out.println(e);
        } catch (DocumentException e) {
            System.out.println(e);
        }

    }
	
	
}
