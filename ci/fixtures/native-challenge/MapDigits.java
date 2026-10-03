// Independently decode the map pixels received by the actual synthetic client.
// No production session/prompt/state is read and no server-side completion setter is used.
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Base64;
public final class MapDigits {
 public static void main(String[] args) throws Exception {
  String encoded=new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).trim();
  if(encoded.length()>22000) throw new IllegalArgumentException("Oversized map");
  byte[] pixels=Base64.getDecoder().decode(encoded);
  if(pixels.length!=16384) throw new IllegalArgumentException("Expected a full 128x128 map packet");
  int[] frequencies=new int[256];for(byte b:pixels)frequencies[b&255]++;
  int background=0;for(int i=1;i<256;i++)if(frequencies[i]>frequencies[background])background=i;
  Font font=new Font(Font.MONOSPACED,Font.BOLD,24);
  BufferedImage probe=new BufferedImage(128,128,BufferedImage.TYPE_INT_RGB);
  Graphics2D measure=probe.createGraphics();measure.setFont(font);int width=measure.getFontMetrics().charWidth('0');measure.dispose();
  StringBuilder answer=new StringBuilder();
  for(int position=0;position<6;position++) {
   int best=-1,score=Integer.MAX_VALUE,second=Integer.MAX_VALUE;
   for(int digit=0;digit<10;digit++) {
    BufferedImage image=new BufferedImage(128,128,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();
    g.setColor(Color.WHITE);g.fillRect(0,0,128,128);g.setFont(font);g.setColor(Color.BLACK);g.drawString(Integer.toString(digit),20+position*width,70);g.dispose();
    int mismatch=0;
    for(int y=38;y<76;y++)for(int x=20+position*width;x<20+(position+1)*width;x++) {
     boolean expected=(image.getRGB(x,y)&0xffffff)!=0xffffff;
     boolean observed=(pixels[y*128+x]&255)!=background;
     if(expected!=observed)mismatch++;
    }
    if(mismatch<score){second=score;score=mismatch;best=digit;}else if(mismatch<second)second=mismatch;
   }
   if(best<0||score>100||second-score<20)throw new IllegalStateException("Map glyph mismatch="+score+", margin="+(second-score));
   answer.append(best);
  }
  System.out.println(answer);
 }
}
