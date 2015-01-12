/*
 * Copyright (C) 2014 Dominik Schürmann <dominik@dominikschuermann.de>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sufficientlysecure.keychain.ui.affirmations;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.sufficientlysecure.keychain.R;

public class AffirmationSelectFragment extends Fragment {

    AffirmationWizard mAffirmationWizard;

    /**
     * Creates new instance of this fragment
     */
    public static AffirmationSelectFragment newInstance() {
        AffirmationSelectFragment frag = new AffirmationSelectFragment();

        Bundle args = new Bundle();
        frag.setArguments(args);

        return frag;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.affirmation_select_fragment, container, false);

        view.findViewById(R.id.affirmation_create_https_button)
                .setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        AffirmationCreateHttpsStep1Fragment frag =
                                AffirmationCreateHttpsStep1Fragment.newInstance();

                        mAffirmationWizard.loadFragment(null, frag, AffirmationWizard.FRAG_ACTION_TO_RIGHT);
                    }
                });

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        mAffirmationWizard = (AffirmationWizard) getActivity();
    }

}
