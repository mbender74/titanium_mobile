package ti.modules.titanium.ui;

import org.appcelerator.titanium.TiContext;
import org.appcelerator.titanium.TiDict;
import org.appcelerator.titanium.view.TiUIView;
import org.appcelerator.titanium.view.TiViewProxy;

import ti.modules.titanium.ui.widget.TiUIActivityIndicator;

public class ActivityIndicatorProxy extends TiViewProxy
{
	public ActivityIndicatorProxy(TiContext tiContext, Object[] args)
	{
		super(tiContext, args);
	}

	@Override
	public TiUIView createView()
	{
		return new TiUIActivityIndicator(this);
	}

	@Override
	protected void handleShow(TiDict options) {
		super.handleShow(options);

		TiUIActivityIndicator ai = (TiUIActivityIndicator) getView();
		ai.show(options);
	}

	@Override
	protected void handleHide(TiDict options) {
		super.handleHide(options);

		TiUIActivityIndicator ai = (TiUIActivityIndicator) getView();
		ai.hide(options);
	}
}
