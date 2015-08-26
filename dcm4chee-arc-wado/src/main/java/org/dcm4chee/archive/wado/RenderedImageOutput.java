/*
 * *** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * J4Care.
 * Portions created by the Initial Developer are Copyright (C) 2015
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * *** END LICENSE BLOCK *****
 */

package org.dcm4chee.archive.wado;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.image.PaletteColorModel;
import org.dcm4che3.image.PixelAspectRatio;
import org.dcm4che3.imageio.plugins.dcm.DicomImageReadParam;
import org.dcm4che3.imageio.plugins.dcm.DicomMetaData;

import javax.imageio.*;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.StreamingOutput;
import java.awt.geom.AffineTransform;
import java.awt.image.*;
import java.io.IOException;
import java.io.OutputStream;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @since Aug 2015
 */
public class RenderedImageOutput implements StreamingOutput {
    private final ImageReader reader;
    private final DicomImageReadParam readParam;
    private final int rows;
    private final int columns;
    private final int imageIndex;
    private final ImageWriter writer;
    private final ImageWriteParam writeParam;

    public RenderedImageOutput(ImageReader reader, DicomImageReadParam readParam, int rows, int columns,
                               int imageIndex, ImageWriter writer, int imageQuality) {
        this.reader = reader;
        this.readParam = readParam;
        this.rows = rows;
        this.columns = columns;
        this.imageIndex = imageIndex;
        this.writer = writer;
        this.writeParam = writer.getDefaultWriteParam();
        if (imageQuality > 0)
            writeParam.setCompressionQuality(imageQuality / 100.f);
    }

    @Override
    public void write(OutputStream out) throws IOException, WebApplicationException {
        try {
            ImageOutputStream imageOut = new MemoryCacheImageOutputStream(out);
            writer.setOutput(imageOut);
            BufferedImage bi = null;
            if (imageIndex < 0) {
                int numImages = reader.getNumImages(false);
                writer.prepareWriteSequence(null);
                for (int i = 0; i < numImages; i++) {
                    readParam.setDestination(bi);
                    bi = reader.read(i, readParam);
                    writer.writeToSequence(new IIOImage(adjust(bi), null, null), writeParam);
                    imageOut.flush();
                }
                writer.endWriteSequence();
            } else {
                bi = reader.read(imageIndex, readParam);
                writer.write(null, new IIOImage(adjust(bi), null, null), writeParam);
            }
            imageOut.flush();
        } finally {
            writer.dispose();
            reader.dispose();
        }
    }

    private BufferedImage adjust(BufferedImage bi) throws IOException {
        return rescale(convertPaletteToIntDiscrete(bi));
    }

    private BufferedImage convertPaletteToIntDiscrete(BufferedImage bi) {
        ColorModel cm = bi.getColorModel();
        return (cm instanceof PaletteColorModel)
                ? ((PaletteColorModel) cm).convertToIntDiscrete(bi.getData())
                : bi;
    }

    private BufferedImage rescale(BufferedImage bi) throws IOException {
        int r = rows;
        int c = columns;
        float sy = getPixelAspectRatio();
        if (r == 0 && c == 0 && sy == 1f)
            return bi;

        float sx = 1f;
        if (r != 0 || c != 0) {
            if (r != 0 && c != 0)
                if (r * bi.getWidth() > c * bi.getHeight() * sy)
                    r = 0;
                else
                    c = 0;
            sx = r != 0 ? r / (bi.getHeight() * sy) : c / (float)bi.getWidth();
            sy *= sx;
        }
        bi = convertBandedToIntDiscrete(bi); // AffineTransformOp does not support BandedSampleModel
        AffineTransformOp op = new AffineTransformOp(
                AffineTransform.getScaleInstance(sx, sy),
                AffineTransformOp.TYPE_NEAREST_NEIGHBOR);
        return op.filter(bi, null);
    }

    private BufferedImage convertBandedToIntDiscrete(BufferedImage bi) {
        WritableRaster raster = bi.getRaster();
        DataBuffer dataBuffer = raster.getDataBuffer();
        if (dataBuffer.getNumBanks() != 3)
            return bi;

        ColorModel cm = new DirectColorModel(bi.getColorModel().getColorSpace(), 24,
                    0xff0000, 0x00ff00, 0x0000ff, 0, false, DataBuffer.TYPE_INT);
        WritableRaster discreteRaster = cm.createCompatibleWritableRaster(raster.getWidth(), raster.getHeight());
        int[] discretData = ((DataBufferInt) discreteRaster.getDataBuffer()).getData();
        byte[][] bankData = ((DataBufferByte) dataBuffer).getBankData();
        byte[] r = bankData[0];
        byte[] g = bankData[1];
        byte[] b = bankData[2];
        for (int i = 0; i < discretData.length; i++)
            discretData[i] = ((r[i] & 0xff) << 16) | ((g[i] & 0xff) << 8) | (b[i] & 0xff);
        return new BufferedImage(cm, discreteRaster, false, null);
    }

    private float getPixelAspectRatio() throws IOException {
        Attributes prAttrs = readParam.getPresentationState();
        return prAttrs != null ? PixelAspectRatio.forPresentationState(prAttrs)
                               : PixelAspectRatio.forImage(getAttributes());
    }

    private Attributes getAttributes() throws IOException {
        return ((DicomMetaData) reader.getStreamMetadata()).getAttributes();
    }
}
