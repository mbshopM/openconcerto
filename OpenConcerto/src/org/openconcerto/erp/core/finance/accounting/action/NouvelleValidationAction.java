/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2011 OpenConcerto, by ILM Informatique. All rights reserved.
 * 
 * The contents of this file are subject to the terms of the GNU General Public License Version 3
 * only ("GPL"). You may not use this file except in compliance with the License. You can obtain a
 * copy of the License at http://www.gnu.org/licenses/gpl-3.0.html See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each file.
 */
 
 package org.openconcerto.erp.core.finance.accounting.action;

import java.awt.Dimension;

import org.openconcerto.erp.action.CreateFrameAbstractAction;
import org.openconcerto.erp.core.common.ui.PanelFrame;
import org.openconcerto.erp.core.finance.accounting.ui.ValidationEcriturePanel;

import javax.swing.Action;
import javax.swing.JFrame;

public class NouvelleValidationAction extends CreateFrameAbstractAction {

    public NouvelleValidationAction() {
        super();
        this.putValue(Action.NAME, "Validation des écritures");
    }

    @Override
    public JFrame createFrame() {
        final PanelFrame panelFrame = new PanelFrame(new ValidationEcriturePanel(), "Validation des écritures");
        panelFrame.setPreferredSize(new Dimension(panelFrame.getPreferredSize().width + 50, panelFrame.getPreferredSize().height));
        panelFrame.pack();
        panelFrame.setResizable(false);
        return panelFrame;
    }
}
