import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.ParagraphListInterface;
import kr.dogfoot.hwplib.object.bodytext.control.*;
import kr.dogfoot.hwplib.object.bodytext.control.table.*;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.*;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.*;
import kr.dogfoot.hwplib.object.bodytext.control.gso.*;
import kr.dogfoot.hwplib.object.bodytext.control.gso.shapecomponent.*;
import kr.dogfoot.hwplib.object.bodytext.control.gso.shapecomponent.lineinfo.*;
import kr.dogfoot.hwplib.object.bodytext.control.gso.shapecomponent.shadowinfo.*;
import kr.dogfoot.hwplib.object.bodytext.control.gso.shapecomponenteach.*;
import kr.dogfoot.hwplib.object.bodytext.paragraph.*;
import kr.dogfoot.hwplib.object.bodytext.paragraph.lineseg.*;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharControlInline;
import kr.dogfoot.hwplib.object.docinfo.*;
import kr.dogfoot.hwplib.object.docinfo.charshape.*;
import kr.dogfoot.hwplib.object.docinfo.parashape.*;
import kr.dogfoot.hwplib.object.docinfo.borderfill.*;
import kr.dogfoot.hwplib.object.docinfo.bindata.*;
import kr.dogfoot.hwplib.object.docinfo.borderfill.fillinfo.*;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.writer.HWPWriter;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwplib.tool.textextractor.*;

/** Independent mapping from our document model to public hwplib objects. */
public final class HwpWriterBridge {
    private final HWPFile file = BlankFileMaker.make();
    private final Map<String,Integer> shapes = new HashMap<>(), fonts = new HashMap<>();
    private static final int WIDTH = 48190;
    private int borderId, paragraphs, tables, images;
    private final List<String> warnings = new ArrayList<>();

    static String s(JsonObject o,String k,String d) { return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():d; }
    static double n(JsonObject o,String k,double d) { return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsDouble():d; }
    static boolean b(JsonObject o,String k) { return o.has(k)&&!o.get(k).isJsonNull()&&o.get(k).getAsBoolean(); }
    static JsonArray a(JsonObject o,String k) { return o.has(k)&&o.get(k).isJsonArray()?o.getAsJsonArray(k):new JsonArray(); }
    static int u(double points) { return (int)Math.round(points*100); }

    void appendText(Paragraph p,String text)throws Exception {
        // HWP paragraph positions count UTF-16 code units, not Unicode code points.
        for(int index=0;index<text.length();index++) {
            char unit=text.charAt(index);
            if(unit=='\t') {
                HWPCharControlInline tab=p.getText().addNewInlineControlChar();
                tab.setCode((short)9);tab.setAddition(new byte[12]);
            } else if(unit=='\n'||unit=='\r') {
                p.getText().addNewCharControlChar().setCode(unit);
            } else if(unit<32) {
                warnings.add("Unsupported control character omitted: "+(int)unit);
            } else p.getText().addNewNormalChar().setCode(unit);
        }
    }

    HwpWriterBridge() {
        Paragraph first = file.getBodyText().getSectionList().get(0).getParagraph(0);
        ControlSectionDefine sec = (ControlSectionDefine)first.getControlList().get(0);
        sec.getPageDef().setLeftMargin(5669); sec.getPageDef().setRightMargin(5669);
        sec.getPageDef().setTopMargin(5669); sec.getPageDef().setBottomMargin(5669);
        sec.getPageDef().setHeaderMargin(0); sec.getPageDef().setFooterMargin(0);
        BorderFill border = file.getDocInfo().getBorderFillList().get(0).clone();
        for(EachBorder edge:Arrays.asList(border.getLeftBorder(),border.getRightBorder(),border.getTopBorder(),border.getBottomBorder())) {
            edge.setType(BorderType.Solid); edge.setThickness(BorderThickness.MM0_12); edge.getColor().setValue(0x808080);
        }
        file.getDocInfo().getBorderFillList().add(border); borderId=file.getDocInfo().getBorderFillList().size();
    }

    int font(String name) {
        if(name.isEmpty())return 1;
        if(fonts.containsKey(name))return fonts.get(name);
        DocInfo d=file.getDocInfo();
        List<ArrayList<FaceName>> lists=Arrays.asList(d.getHangulFaceNameList(),d.getEnglishFaceNameList(),d.getHanjaFaceNameList(),
                d.getJapaneseFaceNameList(),d.getEtcFaceNameList(),d.getSymbolFaceNameList(),d.getUserFaceNameList());
        int id=lists.get(0).size();
        for(ArrayList<FaceName> list:lists){FaceName f=list.get(1).clone();f.setName(name);list.add(f);}
        fonts.put(name,id);return id;
    }

    int shape(JsonObject run,int heading) {
        double size=Math.max(5,Math.min(200,n(run,"size",heading==1?22:heading==2?17:heading>0?13:11)));
        String name=s(run,"font",""),color=s(run,"color","000000").replace("#","");
        boolean bold=b(run,"bold")||heading>0;
        String key=size+":"+name+":"+color+":"+bold+":"+b(run,"italic")+":"+b(run,"underline")+":"+b(run,"strike");
        if(shapes.containsKey(key))return shapes.get(key);
        CharShape c=file.getDocInfo().getCharShapeList().get(0).clone();c.setBaseSize(u(size));c.getFaceNameIds().setForAll(font(name));
        c.getProperty().setBold(bold);c.getProperty().setItalic(b(run,"italic"));c.getProperty().setStrikeLine(b(run,"strike"));
        c.getProperty().setUnderLineSort(b(run,"underline")?UnderLineSort.Bottom:UnderLineSort.None);
        try{int rgb=Integer.parseInt(color,16);c.getCharColor().setValue(((rgb&255)<<16)|(rgb&0xff00)|((rgb>>>16)&255));}
        catch(NumberFormatException ex){warnings.add("Invalid color: "+color);}
        int id=file.getDocInfo().getCharShapeList().size();file.getDocInfo().getCharShapeList().add(c);shapes.put(key,id);return id;
    }

    void fillParagraph(Paragraph p,JsonObject block,int width)throws Exception {
        paragraphs++;int heading=(int)n(block,"heading",0);String align=s(block,"alignment","left");
        ParaShape ps=file.getDocInfo().getParaShapeList().get(3).clone();
        ps.getProperty1().setAlignment(align.equals("center")?Alignment.Center:align.equals("right")?Alignment.Right:align.equals("justify")?Alignment.Justify:Alignment.Left);
        ps.setLineSpace(160);ps.setBottomParaSpace(heading>0?800:500);ps.setTopParaSpace(heading>0?600:0);
        ps.getProperty1().setTogetherNextPara(heading>0);ps.getProperty1().setSplitPageBeforePara(b(block,"page_break_before"));
        p.getHeader().setParaShapeId(file.getDocInfo().getParaShapeList().size());file.getDocInfo().getParaShapeList().add(ps);
        p.getHeader().setStyleId((short)0);if(b(block,"page_break_before"))p.getHeader().getDivideSort().setDividePage(true);
        if(p.getText()==null)p.createText();p.createCharShape();p.getCharShape().addParaCharShape(0,shape(new JsonObject(),heading));
        String prefix=s(block,"list_prefix","");if(!prefix.isEmpty())appendText(p,prefix+" ");
        for(JsonElement e:a(block,"runs")){
            JsonObject run=e.getAsJsonObject();String text=s(run,"text","").replace("\r\n","\n").replace("\r","\n");
            if(text.isEmpty())continue;
            p.getCharShape().addParaCharShape(p.getText().getCharSize(),shape(run,heading));appendText(p,text);
        }
        appendText(p,"\r");p.createLineSeg();LineSegItem line=p.getLineSeg().addNewLineSegItem();
        int height=u(heading==1?22:heading==2?17:heading>0?13:11);
        line.setTextStartPosition(0);line.setLineHeight(height);line.setTextPartHeight(height);
        line.setDistanceBaseLineToLineVerticalPosition((int)(height*.85));line.setLineSpace((int)(height*.6));line.setSegmentWidth(width);line.getTag().setValue(393216);
    }

    Paragraph paragraph(ParagraphListInterface list,JsonObject block,int width)throws Exception {
        Paragraph p=list.addNewParagraph();fillParagraph(p,block,width);return p;
    }

    Paragraph objectParagraph(ParagraphListInterface list,int width)throws Exception {
        Paragraph p=paragraph(list,new JsonObject(),width);p.getText().getCharList().clear();return p;
    }

    void objectHeader(CtrlHeaderGso h,int width,int height) {
        h.getProperty().setLikeWord(true);h.getProperty().setApplyLineSpace(true);
        h.getProperty().setWidthCriterion(WidthCriterion.Absolute);
        h.getProperty().setHeightCriterion(HeightCriterion.Absolute);
        h.setWidth(width);h.setHeight(height);h.setPreventPageDivide(false);
    }

    void table(ParagraphListInterface list,JsonObject block,int width)throws Exception {
        JsonArray rows=a(block,"rows");if(rows.isEmpty())return;
        int columns=Math.max(1,a(block,"col_widths").size());
        for(JsonElement r:rows){int count=0;for(JsonElement c:r.getAsJsonArray())count+=(int)n(c.getAsJsonObject(),"colspan",1);columns=Math.max(columns,count);}
        if(columns>63||rows.size()>10000)throw new IllegalArgumentException("Table exceeds supported dimensions");
        Paragraph p=objectParagraph(list,width);p.getText().addExtendCharForTable();appendText(p,"\r");
        ControlTable t=(ControlTable)p.addNewControl(ControlType.Table);objectHeader(t.getHeader(),width,rows.size()*2500);
        t.getTable().setColumnCount(columns);t.getTable().setBorderFillId(borderId);
        t.getTable().getProperty().setDivideAtPageBoundary(DivideAtPageBoundary.DivideByCell);
        t.getTable().setLeftInnerMargin(300);t.getTable().setRightInnerMargin(300);
        t.getTable().setTopInnerMargin(200);t.getTable().setBottomInnerMargin(200);
        int[] occupiedUntil=new int[columns];int rowIndex=0;
        for(JsonElement rowElement:rows){
            Row row=t.addNewRow();int col=0;
            for(JsonElement cellElement:rowElement.getAsJsonArray()){
                while(col<columns&&occupiedUntil[col]>rowIndex)col++;
                JsonObject model=cellElement.getAsJsonObject();int span=Math.max(1,(int)n(model,"colspan",1));
                int rowspan=Math.max(1,(int)n(model,"rowspan",1));
                if(col+span>columns||rowIndex+rowspan>rows.size())throw new IllegalArgumentException("Invalid merged table cell");
                Cell cell=row.addNewCell();ListHeaderForCell h=cell.getListHeader();int cellWidth=width*span/columns;
                h.setColIndex(col);h.setRowIndex(rowIndex);h.setColSpan(span);h.setRowSpan(rowspan);
                h.setWidth(cellWidth);h.setHeight(2500L*rowspan);
                h.setLeftMargin(300);h.setRightMargin(300);h.setTopMargin(200);h.setBottomMargin(200);h.setBorderFillId(borderId);
                blocks(cell.getParagraphList(),a(model,"blocks"),cellWidth-600,false);
                if(cell.getParagraphList().getParagraphCount()==0)paragraph(cell.getParagraphList(),new JsonObject(),cellWidth-600);
                for(int c=col;c<col+span;c++)occupiedUntil[c]=rowIndex+rowspan;
                col+=span;
            }
            rowIndex++;
        }
        tables++;
    }

    void image(ParagraphListInterface list,JsonObject block,int width)throws Exception {
        Path path=Paths.get(s(block,"path",""));byte[] bytes=Files.readAllBytes(path);
        double w=u(n(block,"width_pt",240)),h=u(n(block,"height_pt",160));
        double scale=Math.min(1,Math.min(width/Math.max(1,w),70000/Math.max(1,h)));
        int iw=Math.max(100,(int)(w*scale)),ih=Math.max(100,(int)(h*scale));
        int id=file.getDocInfo().getBinDataList().size()+1;
        kr.dogfoot.hwplib.object.docinfo.BinData data=file.getDocInfo().addNewBinData();
        data.getProperty().setType(BinDataType.Embedding);data.getProperty().setCompress(BinDataCompress.NoCompress);
        data.setBinDataID(id);data.setExtensionForEmbedding("png");
        file.getBinData().addNewEmbeddedBinaryData(String.format("Bin%04X.png",id),bytes,BinDataCompress.NoCompress);
        Paragraph p=objectParagraph(list,width);p.getText().addExtendCharForGSO();appendText(p,"\r");
        // Embedded image fill on a rectangle follows hwplib's documented interoperable image path.
        ControlRectangle picture=(ControlRectangle)p.addNewGsoControl(GsoControlType.Rectangle);
        objectHeader(picture.getHeader(),iw,ih);
        picture.getHeader().getProperty().setObjectNumberSort(ObjectNumberSort.Figure);
        ShapeComponentNormal sc=(ShapeComponentNormal)picture.getShapeComponent();
        sc.setLocalFileVersion(1);sc.setWidthAtCreate(iw);sc.setHeightAtCreate(ih);
        sc.setWidthAtCurrent(iw);sc.setHeightAtCurrent(ih);sc.setRotateXCenter(iw/2);sc.setRotateYCenter(ih/2);
        sc.createLineInfo();sc.getLineInfo().getProperty().setLineType(LineType.None);
        sc.getLineInfo().setOutlineStyle(OutlineStyle.Normal);sc.getLineInfo().setThickness(0);
        sc.createFillInfo();FillInfo fill=sc.getFillInfo();fill.getType().setImageFill(true);fill.createImageFill();
        fill.getImageFill().setImageFillType(ImageFillType.FitSize);
        fill.getImageFill().getPictureInfo().setEffect(PictureEffect.RealPicture);
        fill.getImageFill().getPictureInfo().setBinItemID(id);
        sc.createShadowInfo();sc.getShadowInfo().setType(ShadowType.None);sc.setMatrixsNormal();
        ShapeComponentRectangle rectangle=picture.getShapeComponentRectangle();
        rectangle.setX1(0);rectangle.setY1(0);rectangle.setX2(iw);rectangle.setY2(0);
        rectangle.setX3(iw);rectangle.setY3(ih);rectangle.setX4(0);rectangle.setY4(ih);images++;
    }

    void blocks(ParagraphListInterface list,JsonArray model,int width,boolean firstAvailable)throws Exception {
        boolean first=firstAvailable;
        for(JsonElement e:model){
            JsonObject block=e.getAsJsonObject();String type=s(block,"type","paragraph");
            if(type.equals("paragraph")){
                if(first)fillParagraph(list.getParagraph(0),block,width);else paragraph(list,block,width);
            }else if(type.equals("table"))table(list,block,width);
            else if(type.equals("image"))image(list,block,width);
            else throw new IllegalArgumentException("Unknown block type: "+type);
            first=false;
        }
    }

    public static void main(String[] args)throws Exception {
        if(args.length==2&&args[0].equals("--inspect")){
            HWPFile h=HWPReader.fromFile(args[1]);JsonObject result=new JsonObject();
            result.addProperty("text",TextExtractor.extract(h,TextExtractMethod.InsertControlTextBetweenParagraphText));
            result.addProperty("sections",h.getBodyText().getSectionList().size());
            result.addProperty("char_shapes",h.getDocInfo().getCharShapeList().size());
            result.addProperty("embedded_images",h.getBinData().getEmbeddedBinaryDataList().size());
            System.out.println(new Gson().toJson(result));return;
        }
        if(args.length!=2)throw new IllegalArgumentException("Usage: HwpWriterBridge input.json output.hwp");
        JsonObject model=JsonParser.parseString(Files.readString(Paths.get(args[0]),StandardCharsets.UTF_8)).getAsJsonObject();
        HwpWriterBridge writer=new HwpWriterBridge();
        writer.blocks(writer.file.getBodyText().getSectionList().get(0),a(model,"blocks"),WIDTH,true);
        HWPWriter.toFile(writer.file,args[1]);HWPReader.fromFile(args[1]);
        JsonObject result=new JsonObject();result.addProperty("paragraphs",writer.paragraphs);
        result.addProperty("tables",writer.tables);result.addProperty("images",writer.images);
        result.add("warnings",new Gson().toJsonTree(writer.warnings));System.out.println(new Gson().toJson(result));
    }
}
