import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.docinfo.Style;
import kr.dogfoot.hwplib.object.bodytext.control.ControlSectionDefine;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.writer.HWPWriter;

/** A distinct template used to prove native style/page inheritance. */
public final class TemplateFixture {
    public static void main(String[] args) throws Exception {
        HWPFile h=HWPReader.fromFile(args[0]);Style st=h.getDocInfo().getStyleList().get(0);
        h.getDocInfo().getCharShapeList().get(st.getCharShapeId()).setBaseSize(1800);
        h.getDocInfo().getCharShapeList().get(st.getCharShapeId()).getCharColor().setValue(0xff0000);
        h.getDocInfo().getParaShapeList().get(st.getParaShapeId()).setBottomParaSpace(2400);
        ((ControlSectionDefine)h.getBodyText().getSectionList().get(0).getParagraph(0).getControlList().get(0)).getPageDef().setLeftMargin(9000);
        HWPWriter.toFile(h,args[1]);
    }
}
