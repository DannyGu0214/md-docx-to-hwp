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
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharControlExtend;
import kr.dogfoot.hwplib.object.bodytext.control.sectiondefine.*;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.autonumber.NumberSort;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.header.HeaderFooterApplyPage;
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
    private int contentWidth = WIDTH, footnotes, links, pageFields;
    private boolean referenceMode;
    private final Map<Integer,Integer> templateStyles = new HashMap<>();
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

    HwpWriterBridge(JsonObject model) throws Exception {
        Paragraph first = file.getBodyText().getSectionList().get(0).getParagraph(0);
        ControlSectionDefine sec = (ControlSectionDefine)first.getControlList().get(0);
        sec.getPageDef().setLeftMargin(5669); sec.getPageDef().setRightMargin(5669);
        sec.getPageDef().setTopMargin(5669); sec.getPageDef().setBottomMargin(5669);
        sec.getPageDef().setHeaderMargin(0); sec.getPageDef().setFooterMargin(0);
        String reference=s(model,"reference_doc","");
        if(!reference.isEmpty()) {
            HWPFile ref=HWPReader.fromFile(reference);
            if(ref.getBodyText().getSectionList().size()!=1)
                throw new IllegalArgumentException("Reference HWP must have one section");
            if (!ref.getBinData().getEmbeddedBinaryDataList().isEmpty())
                throw new IllegalArgumentException("Reference HWP must be a text/style-only template without embedded image assets");
            file.getDocInfo().copy(ref.getDocInfo());file.getDocInfo().getBinDataList().clear();
            ControlSectionDefine refSec=null;
            for(Control control:ref.getBodyText().getSectionList().get(0).getParagraph(0).getControlList())
                if(control instanceof ControlSectionDefine)refSec=(ControlSectionDefine)control;
            if(refSec==null)throw new IllegalArgumentException("Reference HWP has no page definition");
            sec.getPageDef().copy(refSec.getPageDef());referenceMode=true;templateStyles.put(0,0);
            for(int i=0;i<file.getDocInfo().getStyleList().size();i++) {
                Style st=file.getDocInfo().getStyleList().get(i);
                String name=(st.getEnglishName()+" "+st.getHangulName()).toLowerCase(Locale.ROOT);
                for(int level=1;level<=6;level++)
                    if(name.matches(".*(?:heading|제목|개요)\\s*"+level+"(?:\\D.*|$)"))templateStyles.put(level,i);
            }
        } else if(model.has("page")) {
            JsonObject page=model.getAsJsonObject("page");PageDef pd=sec.getPageDef();
            pd.setPaperWidth(u(n(page,"page_width_pt",pd.getPaperWidth()/100.0)));
            pd.setPaperHeight(u(n(page,"page_height_pt",pd.getPaperHeight()/100.0)));
            pd.setLeftMargin(u(n(page,"left_margin_pt",56.69)));pd.setRightMargin(u(n(page,"right_margin_pt",56.69)));
            int top=u(n(page,"top_margin_pt",56.69)),bottom=u(n(page,"bottom_margin_pt",56.69));
            int header=hasContent(a(model,"header"))?Math.min(top,u(n(page,"header_distance_pt",0))):top;
            int footer=hasContent(a(model,"footer"))?Math.min(bottom,u(n(page,"footer_distance_pt",0))):bottom;
            pd.setTopMargin(header);pd.setHeaderMargin(top-header);
            pd.setBottomMargin(footer);pd.setFooterMargin(bottom-footer);
        }
        contentWidth=(int)(sec.getPageDef().getPaperWidth()-sec.getPageDef().getLeftMargin()-sec.getPageDef().getRightMargin()-sec.getPageDef().getGutterMargin());
        if(contentWidth<=600)throw new IllegalArgumentException("Page margins leave no usable content width");
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
        String key=heading+":"+size+":"+name+":"+color+":"+bold+":"+b(run,"italic")+":"+b(run,"underline")+":"+b(run,"strike")+":"+b(run,"superscript")+":"+b(run,"subscript");
        if(shapes.containsKey(key))return shapes.get(key);
        int base=referenceMode?file.getDocInfo().getStyleList().get(templateStyles.getOrDefault(heading,0)).getCharShapeId():0;
        CharShape c=file.getDocInfo().getCharShapeList().get(base).clone();
        if(!referenceMode){c.setBaseSize(u(size));c.getFaceNameIds().setForAll(font(name));}
        c.getProperty().setBold(bold);c.getProperty().setItalic(b(run,"italic"));c.getProperty().setStrikeLine(b(run,"strike"));
        c.getProperty().setSuperScript(b(run,"superscript"));c.getProperty().setSubScript(b(run,"subscript"));
        c.getProperty().setUnderLineSort(b(run,"underline")?UnderLineSort.Bottom:UnderLineSort.None);
        try{int rgb=Integer.parseInt(color,16);if(!referenceMode)c.getCharColor().setValue(((rgb&255)<<16)|(rgb&0xff00)|((rgb>>>16)&255));}
        catch(NumberFormatException ex){warnings.add("Invalid color: "+color);}
        int id=file.getDocInfo().getCharShapeList().size();file.getDocInfo().getCharShapeList().add(c);shapes.put(key,id);return id;
    }

    void fillParagraph(Paragraph p,JsonObject block,int width)throws Exception {
        paragraphs++;int heading=(int)n(block,"heading",0);String align=s(block,"alignment","left");
        int styleId=referenceMode?templateStyles.getOrDefault(heading,0):0;
        int paraId=referenceMode?file.getDocInfo().getStyleList().get(styleId).getParaShapeId():3;
        ParaShape ps=file.getDocInfo().getParaShapeList().get(paraId).clone();
        if(!referenceMode) {
            ps.getProperty1().setAlignment(align.equals("center")?Alignment.Center:align.equals("right")?Alignment.Right:align.equals("justify")?Alignment.Justify:Alignment.Left);
            ps.setLineSpace(160);ps.setLineSpace2(160);
            ps.setBottomParaSpace(u(n(block,"space_after_pt",heading>0?8:5)));ps.setTopParaSpace(u(n(block,"space_before_pt",heading>0?6:0)));
            ps.setLeftMargin(u(n(block,"left_indent_pt",0)));ps.setRightMargin(u(n(block,"right_indent_pt",0)));ps.setIndent(u(n(block,"first_line_indent_pt",0)));
            if(block.has("line_spacing")) {
                JsonObject spacing=block.getAsJsonObject("line_spacing");String kind=s(spacing,"kind","percent");
                LineSpaceSort sort=kind.equals("exact")?LineSpaceSort.FixedValue:kind.equals("minimum")?LineSpaceSort.AtLeast:LineSpaceSort.RatioForLetter;
                int value=kind.equals("percent")?(int)Math.round(n(spacing,"value",160)):u(n(spacing,"value",11));
                ps.getProperty1().setLineSpaceSort(sort);ps.getProperty3().setLineSpaceSort(sort);ps.setLineSpace(value);ps.setLineSpace2(value);
            }
        }
        ps.getProperty1().setTogetherNextPara(block.has("keep_with_next")?b(block,"keep_with_next"):heading>0);
        if(block.has("widow_control"))ps.getProperty1().setProtectLoner(b(block,"widow_control"));
        ps.getProperty1().setProtectPara(b(block,"keep_together"));ps.getProperty1().setSplitPageBeforePara(b(block,"page_break_before"));
        p.getHeader().setParaShapeId(file.getDocInfo().getParaShapeList().size());file.getDocInfo().getParaShapeList().add(ps);
        p.getHeader().setStyleId((short)styleId);if(b(block,"page_break_before"))p.getHeader().getDivideSort().setDividePage(true);
        if(p.getText()==null)p.createText();p.createCharShape();p.getCharShape().addParaCharShape(0,shape(new JsonObject(),heading));
        if(block.has("note_number")){autoNumber(p,NumberSort.FootNote,(int)n(block,"note_number",1));appendText(p," ");}
        String prefix=s(block,"list_prefix","");if(!prefix.isEmpty())appendText(p,prefix+" ");
        for(JsonElement e:a(block,"runs")){
            JsonObject run=e.getAsJsonObject();String text=s(run,"text","").replace("\r\n","\n").replace("\r","\n");
            if(run.has("footnote")){footnote(p,run.getAsJsonArray("footnote"));continue;}
            if(s(run,"field","").equals("page")){autoNumber(p,NumberSort.Page,1);pageFields++;continue;}
            if(text.isEmpty())continue;
            String href=s(run,"href","");
            if(!href.isEmpty())p.getText().addExtendCharForHyperlinkStart();
            p.getCharShape().addParaCharShape(p.getText().getCharSize(),shape(run,heading));appendText(p,text);
            if(!href.isEmpty()){
                p.getText().addExtendCharForHyperlinkEnd();ControlField field=(ControlField)p.addNewControl(ControlType.FIELD_HYPERLINK.getCtrlId());
                field.getHeader().getCommand().fromUTF16LEString(href.replace("\\","\\\\").replace(":","\\:").replace("?","\\?").replace(";","\\;")+";1;0;0;");links++;
            }
        }
        appendText(p,"\r");p.deleteLineSeg();
    }

    void extended(Paragraph p,int code,String id)throws Exception {
        HWPCharControlExtend ch=p.getText().addNewExtendControlChar();ch.setCode((short)code);
        byte[] data=new byte[12];for(int i=0;i<4;i++)data[3-i]=(byte)id.charAt(i);ch.setAddition(data);
    }

    void autoNumber(Paragraph p,NumberSort sort,int number)throws Exception {
        extended(p,0x12,"atno");ControlAutoNumber c=(ControlAutoNumber)p.addNewControl(ControlType.AutoNumber);
        c.getHeader().getProperty().setNumberSort(sort);c.getHeader().getProperty().setNumberShape(NumberShape.Number);
        c.getHeader().getProperty().setSuperScript(sort==NumberSort.FootNote);c.getHeader().setNumber(number);
    }

    void footnote(Paragraph p,JsonArray content)throws Exception {
        extended(p,0x11,"fn  ");ControlFootnote note=(ControlFootnote)p.addNewControl(ControlType.Footnote);
        note.getHeader().setNumber(++footnotes);note.getHeader().setNumberShape(NumberShape.Number);
        JsonArray numbered=content.deepCopy();
        if(numbered.isEmpty()||!s(numbered.get(0).getAsJsonObject(),"type","paragraph").equals("paragraph"))numbered.add(new JsonObject());
        numbered.get(0).getAsJsonObject().addProperty("note_number",footnotes);
        blocks(note.getParagraphList(),numbered,contentWidth,false);
    }

    static boolean hasContent(JsonArray content) {
        for(JsonElement e:content)if(!a(e.getAsJsonObject(),"runs").isEmpty()||!s(e.getAsJsonObject(),"type","").equals("paragraph"))return true;
        return false;
    }

    void headerFooter(Paragraph anchor,JsonArray content,boolean footer)throws Exception {
        if(!hasContent(content))return;
        if(footer){anchor.getText().addExtendCharForFooter();ControlFooter c=(ControlFooter)anchor.addNewControl(ControlType.Footer);
            c.getHeader().setApplyPage(HeaderFooterApplyPage.BothPage);c.getListHeader().setTextWidth(contentWidth);c.getListHeader().setTextHeight(2000);blocks(c.getParagraphList(),content,contentWidth,false);
        }else{anchor.getText().addExtendCharForHeader();ControlHeader c=(ControlHeader)anchor.addNewControl(ControlType.Header);
            c.getHeader().setApplyPage(HeaderFooterApplyPage.BothPage);c.getListHeader().setTextWidth(contentWidth);c.getListHeader().setTextHeight(2000);blocks(c.getParagraphList(),content,contentWidth,false);}
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
        double[] weights=new double[columns];double total=0;JsonArray widths=a(block,"col_widths");
        for(int i=0;i<columns;i++){weights[i]=i<widths.size()?Math.max(0,widths.get(i).getAsDouble()):0;total+=weights[i];}
        boolean absoluteWidths=s(block,"width_unit","").equals("pt")&&total>0&&Arrays.stream(weights).allMatch(v->v>0);
        if(total<=0||Arrays.stream(weights).anyMatch(v->v<=0)){Arrays.fill(weights,1);total=columns;}
        int tableWidth=absoluteWidths?Math.min(width,u(total)):width;
        objectHeader(t.getHeader(),tableWidth,rows.size()*2500);
        int[] boundaries=new int[columns+1];double cumulative=0;
        for(int i=0;i<columns;i++){cumulative+=weights[i];boundaries[i+1]=(int)Math.round(tableWidth*cumulative/total);}
        for(JsonElement rowElement:rows){
            Row row=t.addNewRow();int col=0;
            for(JsonElement cellElement:rowElement.getAsJsonArray()){
                while(col<columns&&occupiedUntil[col]>rowIndex)col++;
                JsonObject model=cellElement.getAsJsonObject();int span=Math.max(1,(int)n(model,"colspan",1));
                int rowspan=Math.max(1,(int)n(model,"rowspan",1));
                if(col+span>columns||rowIndex+rowspan>rows.size())throw new IllegalArgumentException("Invalid merged table cell");
                Cell cell=row.addNewCell();ListHeaderForCell h=cell.getListHeader();int cellWidth=boundaries[col+span]-boundaries[col];
                if(cellWidth<=600)throw new IllegalArgumentException("Table cell is too narrow for its margins");
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

    static JsonArray inspectLayout(HWPFile h,ParagraphListInterface list)throws Exception {
        JsonArray result=new JsonArray();
        for(int i=0;i<list.getParagraphCount();i++) {
            Paragraph p=list.getParagraph(i);JsonObject info=new JsonObject();
            info.addProperty("text",p.getText()==null||p.getText().getCharList().isEmpty()?"":p.getText().getNormalString(0));
            ParaShape ps=h.getDocInfo().getParaShapeList().get(p.getHeader().getParaShapeId());
            info.addProperty("before",ps.getTopParaSpace());info.addProperty("after",ps.getBottomParaSpace());info.addProperty("line_spacing",ps.getLineSpace());
            info.addProperty("line_kind",ps.getProperty3().getLineSpaceSort().name());
            JsonArray chars=new JsonArray();
            if(p.getCharShape()!=null)for(kr.dogfoot.hwplib.object.bodytext.paragraph.charshape.CharPositionShapeIdPair pair:p.getCharShape().getPositonShapeIdPairList()) {
                CharShape ch=h.getDocInfo().getCharShapeList().get((int)pair.getShapeId());JsonObject item=new JsonObject();
                item.addProperty("size",ch.getBaseSize());item.addProperty("color",ch.getCharColor().getValue());
                item.addProperty("super",ch.getProperty().isSuperScript());item.addProperty("sub",ch.getProperty().isSubScript());chars.add(item);
            }
            info.add("characters",chars);JsonArray controls=new JsonArray();
            if(p.getControlList()!=null)for(Control control:p.getControlList()) {
                JsonObject item=new JsonObject();item.addProperty("type",control.getClass().getSimpleName());
                if(control instanceof ControlTable) {
                    JsonArray cells=new JsonArray();for(Row row:((ControlTable)control).getRowList())for(Cell cell:row.getCellList()) {
                        JsonObject ci=new JsonObject();ci.addProperty("width",cell.getListHeader().getWidth());ci.add("paragraphs",inspectLayout(h,cell.getParagraphList()));cells.add(ci);
                    }item.add("cells",cells);
                }else if(control instanceof ControlFootnote)item.add("paragraphs",inspectLayout(h,((ControlFootnote)control).getParagraphList()));
                else if(control instanceof ControlFooter)item.add("paragraphs",inspectLayout(h,((ControlFooter)control).getParagraphList()));
                else if(control instanceof ControlHeader)item.add("paragraphs",inspectLayout(h,((ControlHeader)control).getParagraphList()));
                else if(control instanceof ControlField)item.addProperty("command",((ControlField)control).getHeader().getCommand().toUTF16LEString());
                else if(control instanceof ControlAutoNumber)item.addProperty("number_sort",((ControlAutoNumber)control).getHeader().getProperty().getNumberSort().name());
                controls.add(item);
            }info.add("controls",controls);result.add(info);
        }return result;
    }

    public static void main(String[] args)throws Exception {
        if(args.length==2&&args[0].equals("--inspect")){
            HWPFile h=HWPReader.fromFile(args[1]);JsonObject result=new JsonObject();
            result.addProperty("text",TextExtractor.extract(h,TextExtractMethod.InsertControlTextBetweenParagraphText));
            result.addProperty("sections",h.getBodyText().getSectionList().size());
            result.addProperty("char_shapes",h.getDocInfo().getCharShapeList().size());
            result.addProperty("embedded_images",h.getBinData().getEmbeddedBinaryDataList().size());
            result.add("layout",inspectLayout(h,h.getBodyText().getSectionList().get(0)));
            ControlSectionDefine sec=(ControlSectionDefine)h.getBodyText().getSectionList().get(0).getParagraph(0).getControlList().get(0);
            JsonObject page=new JsonObject();page.addProperty("width",sec.getPageDef().getPaperWidth());page.addProperty("height",sec.getPageDef().getPaperHeight());
            page.addProperty("left",sec.getPageDef().getLeftMargin());page.addProperty("right",sec.getPageDef().getRightMargin());
            page.addProperty("top",sec.getPageDef().getTopMargin());page.addProperty("bottom",sec.getPageDef().getBottomMargin());page.addProperty("header",sec.getPageDef().getHeaderMargin());page.addProperty("footer",sec.getPageDef().getFooterMargin());result.add("page",page);
            System.out.println(new Gson().toJson(result));return;
        }
        if(args.length!=2)throw new IllegalArgumentException("Usage: HwpWriterBridge input.json output.hwp");
        JsonObject model=JsonParser.parseString(Files.readString(Paths.get(args[0]),StandardCharsets.UTF_8)).getAsJsonObject();
        HwpWriterBridge writer=new HwpWriterBridge(model);
        writer.blocks(writer.file.getBodyText().getSectionList().get(0),a(model,"blocks"),writer.contentWidth,true);
        Paragraph anchor=writer.file.getBodyText().getSectionList().get(0).getParagraph(0);
        writer.headerFooter(anchor,a(model,"header"),false);writer.headerFooter(anchor,a(model,"footer"),true);
        HWPWriter.toFile(writer.file,args[1]);HWPReader.fromFile(args[1]);
        JsonObject result=new JsonObject();result.addProperty("paragraphs",writer.paragraphs);
        result.addProperty("tables",writer.tables);result.addProperty("images",writer.images);
        result.addProperty("footnotes",writer.footnotes);result.addProperty("hyperlinks",writer.links);result.addProperty("page_fields",writer.pageFields);
        result.add("warnings",new Gson().toJsonTree(writer.warnings));System.out.println(new Gson().toJson(result));
    }
}
