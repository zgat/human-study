"""Check delivered numeric repairs against independent source references, not stored targetY."""
from pathlib import Path
import json
import sys
import unittest
import numpy as np

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/content'))
import anchor_points as g
from cun_units import normalized_location,torso_scales


class CunRepairTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.points={p['id']:p for p in json.loads((g.OUT/'acupoints.json').read_text())['points']}

    def position(self,pid,side):
        return np.asarray(next(i['anchor']['position'] for i in self.points[pid]['instances'] if i['side']==side))

    def test_lower_abdominal_transverse_columns_do_not_use_vertical_five_cun(self):
        for side in ['left','right']:
            for pid,count in [('st26',2),('st30',2),('sp13',4),('sp14',4),('extra-tituo',4),('ex-ca1',3),('lr12',2.5),('ki15',.5)]:
                self.assertAlmostEqual(abs(self.position(pid,side)[0]),count*.026,places=6)
            self.assertAlmostEqual(self.position('st26',side)[1],self.position('ki15',side)[1],places=6)
            self.assertAlmostEqual(self.position('st26',side)[1],self.position('cv7','midline')[1],places=6)

    def test_chest_vertical_nine_and_abdominal_eight_five_are_separate(self):
        scales=torso_scales(g.L)
        y=self.position('cv21','midline')[1]
        self.assertAlmostEqual(y,g.L['sternal-notch'][1]-(g.L['sternal-notch'][1]-g.L['xiphoid-tip'][1])/9,places=6)
        self.assertGreater(abs(scales['chest_vertical']-.026),.007)
        self.assertNotEqual(scales['upper_abdomen_vertical'],scales['lower_abdomen_vertical'])
        self.assertEqual(normalized_location('脐中下l寸；旁开４寸'), '脐中下1寸;旁开4寸')

    def test_referenced_points_use_their_own_segment_and_retained_base(self):
        for side in ['left','right']:
            medial=(g.L[side+'-patella-tip'][1]-g.L[side+'-ankle-medial'][1])/15
            lateral=(g.L[side+'-patella-tip'][1]-g.L[side+'-ankle-lateral'][1])/16
            self.assertAlmostEqual(self.position('sp8',side)[1],self.position('sp9',side)[1]-3*medial,places=6)
            self.assertAlmostEqual(self.position('ki9',side)[1],self.position('ki3',side)[1]+5*medial,places=6)
            self.assertAlmostEqual(self.position('st40',side)[1],g.L[side+'-ankle-lateral'][1]+8*lateral,places=6)
            for pid,n in [('st36',3),('st37',6),('st38',8),('st39',9)]:
                self.assertAlmostEqual(self.position(pid,side)[1],self.position('st35',side)[1]-n*lateral,places=6)
            for pid,n in [('bl58',7),('bl59',3)]:
                self.assertAlmostEqual(self.position(pid,side)[1],self.position('bl60',side)[1]+n*lateral,places=6)

    def test_te9_uses_the_real_ulna_superior_extremum_not_elbow_midpoint(self):
        for side in ['left','right']:
            top=g.vertices(g.find(side+' ulna'))[:,1].max()
            forearm=(g.L[side+'-elbow'][1]-g.L[side+'-wrist'][1])/12
            self.assertAlmostEqual(self.position('te9',side)[1],top-5*forearm,places=6)

    def test_posterior_thigh_uses_14_cun_with_the_explicitly_unreviewed_fold_base(self):
        for side in ['left','right']:
            fold=self.position('bl36',side)[1];pop=g.L[side+'-patella-tip'][1];unit=(fold-pop)/14
            self.assertAlmostEqual(self.position('bl37',side)[1],fold-6*unit,places=6)
            self.assertAlmostEqual(self.position('bl38',side)[1],pop+unit,places=6)

    def test_every_new_projection_reconstructs_on_the_original_source_triangle(self):
        count=0
        for point in self.points.values():
            for item in point['instances']:
                a=item['anchor']
                if 'sourceTriangle' not in a:continue
                triangle=g.vertices(a['sourceSurface'])[np.asarray(a['sourceTriangle'])-1]
                weights=np.asarray(a['barycentric']);self.assertAlmostEqual(weights.sum(),1,places=7)
                self.assertGreaterEqual(weights.min(),-1e-7)
                np.testing.assert_allclose(weights@triangle,a['position'],atol=6e-8,rtol=0)
                if 'measurementEvidence' in a:
                    e=a['measurementEvidence'];self.assertFalse(e.get('medicallyReviewed',False))
                    if 'targetY' in e:self.assertAlmostEqual(a['position'][1],e['targetY'],places=6)
                count+=1
        self.assertGreaterEqual(count,153)

    def test_formal_xiphoid_wording_does_not_move_the_two_existing_tip_based_points(self):
        self.assertIn('剑突尖下1寸',self.points['cv15']['locationZh'])
        self.assertIn('剑突尖所在处',self.points['cv16']['locationZh'])
        self.assertAlmostEqual(self.position('cv16','midline')[1],g.L['xiphoid-tip'][1],places=6)

    def test_lr11_is_directly_below_same_side_st30_on_the_real_skin(self):
        # Derive the denominator endpoints from source vertices independently of
        # the generated targetY, unitMetres and cached landmark positions.
        pubic_y=[]
        for side in ['left','right']:
            hip=g.vertices(g.find(side+' hip bone'))
            pubic_y.append(hip[(abs(hip[:,0])<.02)&(hip[:,2]>.11),1].max())
        for side,sign in [('left',1),('right',-1)]:
            with self.subTest(side=side):
                base=self.position('st30',side)
                patella_y=g.vertices(g.find(side+' patella'))[:,1].max()
                unit=(np.mean(pubic_y)-patella_y)/18
                point=self.position('lr11',side)
                self.assertGreater(sign*point[0],0)
                self.assertAlmostEqual(point[0],base[0],places=7)
                self.assertAlmostEqual(base[1]-point[1],2*unit,places=6)
                anchor=next(i['anchor'] for i in self.points['lr11']['instances'] if i['side']==side)
                self.assertEqual(anchor['sourceSurface'],'FJ2810')
                triangle=g.vertices('FJ2810')[np.asarray(anchor['sourceTriangle'])-1]
                self.assertTrue(np.all(sign*triangle[:,0]>0))
                np.testing.assert_allclose(np.asarray(anchor['barycentric'])@triangle,point,atol=6e-8,rtol=0)
                self.assertGreater(anchor['normal'][2],0)
                evidence=anchor['measurementEvidence']
                self.assertEqual(evidence['segment'],'anterior_thigh_18')
                self.assertEqual(evidence['denominatorCun'],18)
                self.assertEqual(evidence['referencePointId'],'st30')
                self.assertEqual(evidence['sameSideRequired'],side)
                self.assertFalse(evidence['nearestVertexFallback'])
                self.assertFalse(evidence['medicallyReviewed'])
                self.assertAlmostEqual(evidence['unitMetres'],unit,places=12)

    def test_lr11_generation_selects_anterior_surface_without_oblique_medial_ray(self):
        point=self.points['lr11']
        mapped={'st30':{side:self.position('st30',side) for side in ['left','right']}}
        for side in ['left','right']:
            with self.subTest(side=side):
                position,_,method=g.limb(point,side,mapped)
                self.assertIn('same-side-st30',method)
                np.testing.assert_allclose(position,self.position('lr11',side),atol=6e-8,rtol=0)
                # Independently require the source triangle selected by the
                # fixed-X/Y anterior ray, rather than just a nearby vertex.
                self.assertIn(tuple(position),g.PROJECTIONS)
                self.assertEqual(g.MEASUREMENT_EVIDENCE[('lr11',side)]['referencePosition'],mapped['st30'][side].tolist())
                faces=np.array([[int(t.split('/')[0])-1 for t in line.split()[1:4]]
                                for line in g.RAW['FJ2810'].splitlines() if line.startswith('f ')])
                triangles=g.vertices('FJ2810')[faces]
                # Solve each source triangle in XY independently, retaining all
                # shell crossings; the delivered point must be the foremost Z.
                xy=position[:2]
                candidates=triangles[(triangles[:,:,:2].min(axis=1)<=xy).all(axis=1)&
                                     (triangles[:,:,:2].max(axis=1)>=xy).all(axis=1)]
                matrix=np.stack((candidates[:,1,:2]-candidates[:,0,:2],
                                 candidates[:,2,:2]-candidates[:,0,:2]),axis=2)
                good=abs(np.linalg.det(matrix))>1e-14
                candidates=candidates[good];matrix=matrix[good]
                uv=np.linalg.solve(matrix,(xy-candidates[:,0,:2])[...,None])[...,0]
                weights=np.c_[1-uv.sum(axis=1),uv]
                crossings=np.einsum('ij,ij->i',weights,candidates[:,:,2])[(weights>=-1e-9).all(axis=1)]
                self.assertGreaterEqual(len(crossings),2)
                self.assertAlmostEqual(position[2],crossings.max(),places=12)

    def test_lr11_refuses_missing_crossed_or_unprojectable_st30_reference(self):
        point=self.points['lr11']
        for mapped in [{},{'st30':{'left':self.position('st30','right')}},
                       {'st30':{'left':np.array([.8,self.position('st30','left')[1],.17])}}]:
            with self.subTest(mapped=str(mapped)):
                with self.assertRaises(ValueError):g.limb(point,'left',mapped)


if __name__=='__main__':unittest.main()
